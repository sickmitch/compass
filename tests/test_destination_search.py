import asyncio
import json
from uuid import uuid4

import httpx
import pytest
from pydantic import ValidationError

from compass.api.main import app
from compass.config import Settings, get_api_settings
from compass.routing.domain import Coordinate
from compass.search.dependencies import get_destination_search_service
from compass.search.destination_domain import (
    AddressComponent,
    DestinationNotFoundError,
    DestinationProviderError,
    DestinationRateLimitedError,
    DestinationSearchContext,
    DestinationSearchUnavailableError,
    DestinationSuggestion,
    DestinationSuggestRequest,
    DestinationUnresolvableError,
    NormalizedAddress,
    ResolvedDestinationSelection,
    StaleDestinationSelectionError,
)
from compass.search.destination_service import DestinationSearchService
from compass.search.google_places import (
    GOOGLE_PLACES_AUTOCOMPLETE_FIELD_MASK,
    GOOGLE_PLACES_DETAILS_FIELD_MASK,
    GooglePlacesNewDestinationProvider,
)


def _request(*, revision: int = 1, query: str = "Libreria Verona", session: str | None = None):
    return DestinationSuggestRequest(
        query=query,
        session_id=session or str(uuid4()),
        revision=revision,
        context=DestinationSearchContext(Coordinate(45.4384, 10.9916), 25_000),
    )


def _prediction(place_id: str, title: str, subtitle: str, *, distance: int = 120):
    return {
        "placePrediction": {
            "placeId": place_id,
            "text": {"text": f"{title}, {subtitle}"},
            "structuredFormat": {
                "mainText": {"text": title},
                "secondaryText": {"text": subtitle},
            },
            "types": ["establishment", "point_of_interest"],
            "distanceMeters": distance,
        }
    }


def test_google_autocomplete_contract_and_exact_place_id_deduplication() -> None:
    captured: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={
                "suggestions": [
                    _prediction("ChIJ-one", "Libreria Uno", "Via Roma 1, Verona"),
                    _prediction("ChIJ-one", "Libreria Uno", "Via Roma 1, Verona"),
                    _prediction("ChIJ-two", "Libreria Uno", "Via Roma 2, Verona"),
                ]
            },
        )

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = GooglePlacesNewDestinationProvider(
                base_url="https://places.googleapis.com/v1",
                api_key="secret",
                timeout_seconds=2,
                client=client,
            )
            return await provider.suggest(_request(), "provider-session")

    results = asyncio.run(run())
    assert [result.provider_ref for result in results] == ["ChIJ-one", "ChIJ-two"]
    assert [result.provider_rank for result in results] == [0, 2]
    assert results[0].subtitle == "Via Roma 1, Verona"
    assert results[0].distance_meters == 120
    request = captured.single() if hasattr(captured, "single") else captured[0]
    assert request.method == "POST"
    assert request.url.path == "/v1/places:autocomplete"
    assert request.headers["X-Goog-Api-Key"] == "secret"
    assert request.headers["X-Goog-FieldMask"] == GOOGLE_PLACES_AUTOCOMPLETE_FIELD_MASK
    payload = json.loads(request.content)
    assert payload["sessionToken"] == "provider-session"
    assert payload["includeQueryPredictions"] is False
    assert payload["includePureServiceAreaBusinesses"] is False
    assert payload["includedRegionCodes"] == ["it"]
    assert payload["origin"] == {"latitude": 45.4384, "longitude": 10.9916}
    assert payload["locationBias"]["circle"]["radius"] == 25_000
    assert "includedPrimaryTypes" not in payload
    assert "locationRestriction" not in payload


def test_google_autocomplete_restricts_results_to_route_rectangle() -> None:
    captured: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json={"suggestions": []})

    request = DestinationSuggestRequest(
        query="farmacia",
        session_id=str(uuid4()),
        revision=1,
        context=DestinationSearchContext(
            location=Coordinate(45.4, 10.9),
            route_bounds=(Coordinate(44.9, 10.3), Coordinate(45.8, 11.7)),
        ),
    )

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = GooglePlacesNewDestinationProvider(
                base_url="https://places.googleapis.com/v1",
                api_key="secret",
                timeout_seconds=2,
                client=client,
            )
            await provider.suggest(request, "provider-session")

    asyncio.run(run())
    payload = json.loads(captured[0].content)
    assert payload["origin"] == {"latitude": 45.4, "longitude": 10.9}
    assert payload["locationRestriction"] == {
        "rectangle": {
            "low": {"latitude": 44.9, "longitude": 10.3},
            "high": {"latitude": 45.8, "longitude": 11.7},
        }
    }
    assert "locationBias" not in payload


def test_google_details_resolves_selected_id_and_components_by_type() -> None:
    captured: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={
                "id": "place/with slash",
                "formattedAddress": "Via Roma 12, 37100 Verona VR, Italia",
                "addressComponents": [
                    {"longText": "Verona", "shortText": "Verona", "types": ["locality"]},
                    {"longText": "12", "shortText": "12", "types": ["street_number"]},
                    {"longText": "Via Roma", "shortText": "Via Roma", "types": ["route"]},
                ],
                "location": {"latitude": 45.44, "longitude": 10.99},
                "types": ["street_address"],
            },
        )

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = GooglePlacesNewDestinationProvider(
                base_url="https://places.googleapis.com/v1",
                api_key="secret",
                timeout_seconds=2,
                client=client,
            )
            return await provider.resolve(
                "place/with slash", language="it", provider_session_token="same-token"
            )

    resolved = asyncio.run(run())
    assert resolved.coordinate == Coordinate(45.44, 10.99)
    assert resolved.formatted_address.startswith("Via Roma 12")
    assert resolved.address_components[0].types == ("locality",)
    assert resolved.address_components[1].types == ("street_number",)
    assert resolved.normalized_address.street == "Via Roma"
    assert resolved.normalized_address.street_number == "12"
    assert resolved.normalized_address.locality == "Verona"
    request = captured[0]
    assert request.url.raw_path.startswith(b"/v1/places/place%2Fwith%20slash")
    assert request.url.params["sessionToken"] == "same-token"
    assert request.headers["X-Goog-FieldMask"] == GOOGLE_PLACES_DETAILS_FIELD_MASK
    assert "displayName" not in GOOGLE_PLACES_DETAILS_FIELD_MASK


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (403, DestinationProviderError),
        (404, DestinationNotFoundError),
        (429, DestinationRateLimitedError),
        (503, DestinationSearchUnavailableError),
    ],
)
def test_google_maps_http_failures_to_domain_errors(status, expected) -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, headers={"Retry-After": "7"})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = GooglePlacesNewDestinationProvider(
                base_url="https://places.invalid/v1",
                api_key="secret",
                timeout_seconds=2,
                client=client,
            )
            await provider.suggest(_request(), "token")

    with pytest.raises(expected):
        asyncio.run(run())


def test_google_timeout_and_empty_results_are_distinct() -> None:
    async def timeout(_request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("fixture")

    async def empty(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={})

    async def run(handler):
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = GooglePlacesNewDestinationProvider(
                base_url="https://places.invalid/v1",
                api_key="secret",
                timeout_seconds=2,
                client=client,
            )
            return await provider.suggest(_request(), "token")

    with pytest.raises(DestinationSearchUnavailableError):
        asyncio.run(run(timeout))
    assert asyncio.run(run(empty)) == ()


@pytest.mark.parametrize(
    "location",
    [
        None,
        {},
        {"latitude": None, "longitude": 10},
        {"latitude": 91, "longitude": 10},
        {"latitude": float("nan"), "longitude": 10},
    ],
)
def test_google_details_rejects_missing_or_invalid_coordinates(location) -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        if location and location.get("latitude") != location.get("latitude"):
            return httpx.Response(
                200, content=b'{"id":"id","location":{"latitude":NaN,"longitude":10}}'
            )
        return httpx.Response(200, json={"id": "id", "location": location})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            provider = GooglePlacesNewDestinationProvider(
                base_url="https://places.invalid/v1",
                api_key="secret",
                timeout_seconds=2,
                client=client,
            )
            await provider.resolve("id", language="it", provider_session_token="token")

    with pytest.raises(DestinationUnresolvableError):
        asyncio.run(run())


class _Provider:
    provider_name = "google_places_new"

    def __init__(self) -> None:
        self.suggest_calls: list[tuple[DestinationSuggestRequest, str]] = []
        self.resolve_calls: list[tuple[str, str]] = []
        self.release_suggest = asyncio.Event()
        self.release_suggest.set()
        self.release_resolve = asyncio.Event()
        self.release_resolve.set()

    async def suggest(self, request, provider_session_token):
        self.suggest_calls.append((request, provider_session_token))
        await self.release_suggest.wait()
        return (
            DestinationSuggestion(
                id="google_places_new:id",
                provider=self.provider_name,
                provider_ref="id",
                kind="business",
                title=request.query,
                subtitle="Via Roma 1, Verona",
                address_preview="Via Roma 1, Verona",
                distance_meters=None,
                provider_rank=0,
            ),
        )

    async def resolve(self, provider_ref, *, language, provider_session_token):
        self.resolve_calls.append((provider_ref, provider_session_token))
        await self.release_resolve.wait()
        return ResolvedDestinationSelection(
            provider=self.provider_name,
            provider_ref=provider_ref,
            formatted_address="Via Roma 1, Verona",
            address_components=(AddressComponent("1", "1", ("street_number",)),),
            normalized_address=NormalizedAddress(street_number="1", locality="Verona"),
            coordinate=Coordinate(45.44, 10.99),
            kind="business",
            attribution=("Google Maps",),
            field_sources=(
                ("formatted_address", "google_places_new"),
                ("address_components", "google_places_new"),
                ("location", "google_places_new"),
            ),
        )


def test_service_reuses_token_coalesces_details_and_strips_map_text() -> None:
    async def run():
        provider = _Provider()
        service = DestinationSearchService(providers=(provider,))
        session = str(uuid4())
        request = _request(session=session)
        suggestions = await service.suggest("driver", request)
        first, second = await asyncio.gather(
            service.resolve(
                owner="driver",
                session_id=session,
                revision=1,
                provider="google_places_new",
                provider_ref="id",
            ),
            service.resolve(
                owner="driver",
                session_id=session,
                revision=1,
                provider="google_places_new",
                provider_ref="id",
            ),
        )
        return provider, service, suggestions, first, second

    provider, service, suggestions, first, second = asyncio.run(run())
    assert len(provider.suggest_calls) == 1
    assert len(provider.resolve_calls) == 1
    assert provider.suggest_calls[0][1] == provider.resolve_calls[0][1]
    assert suggestions[0].title == "Libreria Verona"
    assert first == second
    target = first[1]
    assert target.map_label == "Destinazione selezionata"
    assert not hasattr(target, "formatted_address")
    assert service.metrics.tomtom_destination_calls == 0
    assert service.metrics.resolutions_succeeded == 1


def test_service_rejects_out_of_order_response_and_unsuggested_selection() -> None:
    async def run():
        provider = _Provider()
        provider.release_suggest.clear()
        service = DestinationSearchService(providers=(provider,))
        session = str(uuid4())
        old = asyncio.create_task(
            service.suggest("driver", _request(session=session, revision=1, query="old"))
        )
        await asyncio.sleep(0)
        new = asyncio.create_task(
            service.suggest("driver", _request(session=session, revision=2, query="new"))
        )
        await asyncio.sleep(0)
        provider.release_suggest.set()
        outcomes = await asyncio.gather(old, new, return_exceptions=True)
        with pytest.raises(StaleDestinationSelectionError):
            await service.resolve(
                owner="driver",
                session_id=session,
                revision=2,
                provider="google_places_new",
                provider_ref="other",
            )
        return outcomes

    outcomes = asyncio.run(run())
    assert isinstance(outcomes[0], StaleDestinationSelectionError)
    assert outcomes[1][0].title == "new"


def test_service_honours_google_retry_after_without_fallback_or_immediate_retry() -> None:
    class RateLimitedProvider(_Provider):
        async def suggest(self, request, provider_session_token):
            self.suggest_calls.append((request, provider_session_token))
            raise DestinationRateLimitedError(7)

    async def run():
        provider = RateLimitedProvider()
        service = DestinationSearchService(providers=(provider,))
        with pytest.raises(DestinationRateLimitedError) as first:
            await service.suggest("driver", _request())
        with pytest.raises(DestinationRateLimitedError) as second:
            await service.suggest("driver", _request())
        return provider, service, first.value, second.value

    provider, service, first, second = asyncio.run(run())
    assert first.retry_after_seconds == 7
    assert second.retry_after_seconds in range(1, 8)
    assert len(provider.suggest_calls) == 1
    assert service.metrics.tomtom_destination_calls == 0


def test_google_search_configuration_requires_verified_eea_and_preserves_traffic_flags() -> None:
    with pytest.raises(ValidationError, match="contract_regime"):
        Settings(
            _env_file=None,
            destination_search_providers="google_places_new",
            google_places_enabled=True,
            google_places_api_key="secret",
            geocoding_provider="none",
        )
    settings = Settings(
        _env_file=None,
        destination_search_providers="google_places_new",
        google_places_enabled=True,
        google_places_api_key="secret",
        google_places_contract_regime="eea",
        geocoding_provider="none",
        traffic_enabled=True,
        traffic_provider="tomtom",
        tomtom_api_key="traffic-secret",
    )
    assert settings.tomtom_search_enabled is False
    assert settings.traffic_provider == "tomtom"
    assert settings.tomtom_api_key == "traffic-secret"


def test_destination_api_keeps_selection_text_separate_from_map_target() -> None:
    provider = _Provider()
    service = DestinationSearchService(providers=(provider,))

    async def override_service():
        return service

    async def override_settings():
        return Settings(_env_file=None, geocoding_provider="none")

    async def run():
        session = str(uuid4())
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            suggested = await client.post(
                "/api/v1/destinations/suggest",
                json={
                    "query": "Libreria Verona",
                    "session_id": session,
                    "revision": 1,
                    "context": {"location": {"latitude": 45.44, "longitude": 10.99}},
                },
            )
            resolved = await client.post(
                "/api/v1/destinations/resolve",
                json={
                    "session_id": session,
                    "revision": 1,
                    "provider": "google_places_new",
                    "provider_ref": "id",
                },
            )
            metrics = await client.get("/api/v1/destinations/metrics")
        return suggested, resolved, metrics

    app.dependency_overrides[get_destination_search_service] = override_service
    app.dependency_overrides[get_api_settings] = override_settings
    try:
        suggested, resolved, metrics = asyncio.run(run())
    finally:
        app.dependency_overrides.clear()

    assert suggested.status_code == 200
    assert suggested.json()["maximum_results"] == 5
    assert "location" not in suggested.json()["results"][0]
    assert resolved.status_code == 200
    body = resolved.json()
    assert body["selection"]["formatted_address"] == "Via Roma 1, Verona"
    assert body["navigation_target"] == {
        "location": {"latitude": 45.44, "longitude": 10.99},
        "provider_ref": "id",
        "map_label": "Destinazione selezionata",
    }
    assert "formatted_address" not in body["navigation_target"]
    assert metrics.json()["tomtom_destination_calls"] == 0


def test_destination_api_maps_stale_and_malformed_sessions() -> None:
    provider = _Provider()
    service = DestinationSearchService(providers=(provider,))

    async def override_service():
        return service

    async def override_settings():
        return Settings(_env_file=None, geocoding_provider="none")

    async def run():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            malformed = await client.post(
                "/api/v1/destinations/suggest",
                json={"query": "Verona", "session_id": "0" * 36, "revision": 1},
            )
            stale = await client.post(
                "/api/v1/destinations/resolve",
                json={
                    "session_id": str(uuid4()),
                    "revision": 1,
                    "provider": "google_places_new",
                    "provider_ref": "not-suggested",
                },
            )
        return malformed, stale

    app.dependency_overrides[get_destination_search_service] = override_service
    app.dependency_overrides[get_api_settings] = override_settings
    try:
        malformed, stale = asyncio.run(run())
    finally:
        app.dependency_overrides.clear()
    assert malformed.status_code == 422
    assert malformed.json()["code"] == "invalid_request"
    assert stale.status_code == 409
    assert stale.json()["code"] == "stale_selection"
