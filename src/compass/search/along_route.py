from __future__ import annotations

import asyncio
import hashlib
import json
import time
import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field, fields, replace
from math import asin, cos, isfinite, radians, sin, sqrt
from typing import Any, Literal, Protocol
from uuid import UUID

import httpx

from compass.candidates.geometry import decode_polyline6, encode_polyline5
from compass.routing.domain import (
    Coordinate,
    NoRouteError,
    RoutingProvider,
    RoutingProviderError,
    RoutingUnavailableError,
    WaypointRouteRequest,
)
from compass.search.destination_domain import (
    DestinationProviderError,
    DestinationRateLimitedError,
    DestinationSearchUnavailableError,
    DestinationSuggestion,
    NavigationTarget,
    NormalizedAddress,
    ResolvedDestinationSelection,
    StaleDestinationSelectionError,
)
from compass.search.destination_service import PerUserRequestLimiter

GOOGLE_SEARCH_ALONG_ROUTE_FIELD_MASK = ",".join(
    (
        "places.id",
        "places.displayName",
        "places.formattedAddress",
        "places.location",
        "places.types",
        "places.attributions",
        "nextPageToken",
    )
)
ROUTE_BIAS_LIMITATION = (
    "I risultati sono orientati alla rotta dal provider; non rappresentano "
    "un corridoio geometrico rigido."
)
EXISTING_WAYPOINT_DUPLICATE_RADIUS_METERS = 25.0


class RouteRequiredError(Exception):
    pass


class InvalidRouteContextError(Exception):
    pass


class UnsupportedRouteGeometryError(Exception):
    pass


@dataclass(frozen=True, slots=True)
class AlongRouteLeg:
    encoded_polyline6: str


@dataclass(frozen=True, slots=True)
class AlongRouteContext:
    route_id: str
    route_revision: int
    origin: Coordinate
    final_destination: Coordinate
    remaining_waypoints: tuple[Coordinate, ...]
    legs: tuple[AlongRouteLeg, ...]
    allow_highways: bool = True
    progress_shape_index: int | None = None
    insertion_leg_index: int | None = None
    baseline_duration_seconds: float | None = None
    current_duration_seconds: float | None = None
    maximum_total_added_duration_seconds: float | None = None

    def __post_init__(self) -> None:
        durations = (
            self.baseline_duration_seconds,
            self.current_duration_seconds,
            self.maximum_total_added_duration_seconds,
        )
        if any(value is not None and (not isfinite(value) or value < 0) for value in durations):
            raise ValueError("along-route durations must be finite and non-negative")
        if any(value is None for value in durations) and any(
            value is not None for value in durations
        ):
            raise ValueError("along-route time policy must be provided completely")


@dataclass(frozen=True, slots=True)
class AlongRouteSearchRequest:
    query: str
    session_id: str
    revision: int
    route: AlongRouteContext | None
    language: str = "it"
    page_cursor: str | None = None
    full_search: bool = False


@dataclass(frozen=True, slots=True)
class AlongRouteProviderResult:
    suggestion: DestinationSuggestion
    selection: ResolvedDestinationSelection


@dataclass(frozen=True, slots=True)
class AlongRouteProviderResponse:
    results: tuple[AlongRouteProviderResult, ...]
    next_page_token: str | None


@dataclass(frozen=True, slots=True)
class AlongRouteSearchResponse:
    session_id: str
    revision: int
    route_id: str
    route_revision: int
    route_fingerprint: str
    results: tuple[DestinationSuggestion, ...]
    next_page_cursor: str | None
    mode: str = "route_biased"
    limitation: str = ROUTE_BIAS_LIMITATION


class AlongRouteSearchProvider(Protocol):
    provider_name: str

    async def search(
        self,
        *,
        query: str,
        language: str,
        encoded_polyline5: str | None,
        page_token: str | None,
    ) -> AlongRouteProviderResponse: ...


class GooglePlacesNewAlongRouteProvider:
    provider_name = "google_places_new"

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        timeout_seconds: float,
        region_code: str,
        page_size: int,
        client: httpx.AsyncClient,
    ) -> None:
        if not api_key:
            raise ValueError("Google Places API key must not be blank")
        self._url = f"{base_url.rstrip('/')}/places:searchText"
        self._api_key = api_key
        self._timeout = httpx.Timeout(timeout_seconds)
        self._region_code = region_code.upper()
        self._page_size = page_size
        self._client = client

    async def search(
        self,
        *,
        query: str,
        language: str,
        encoded_polyline5: str | None,
        page_token: str | None,
    ) -> AlongRouteProviderResponse:
        payload: dict[str, Any] = {
            "textQuery": query,
            "languageCode": language,
            "regionCode": self._region_code,
            "pageSize": self._page_size,
        }
        if encoded_polyline5 is not None:
            payload["searchAlongRouteParameters"] = {
                "polyline": {"encodedPolyline": encoded_polyline5}
            }
        if page_token is not None:
            payload["pageToken"] = page_token
        try:
            response = await self._client.post(
                self._url,
                json=payload,
                headers={
                    "Content-Type": "application/json",
                    "X-Goog-Api-Key": self._api_key,
                    "X-Goog-FieldMask": GOOGLE_SEARCH_ALONG_ROUTE_FIELD_MASK,
                },
                timeout=self._timeout,
            )
        except httpx.TransportError as error:
            raise DestinationSearchUnavailableError("Google Places is unavailable") from error
        if response.status_code == 429:
            retry_after = response.headers.get("Retry-After")
            raise DestinationRateLimitedError(
                int(retry_after) if retry_after and retry_after.isdigit() else None
            )
        if response.status_code >= 500:
            raise DestinationSearchUnavailableError(
                f"Google Places returned HTTP {response.status_code}"
            )
        if response.status_code >= 300:
            raise DestinationProviderError(
                f"Google Places rejected Search Along Route with HTTP {response.status_code}"
            )
        try:
            body = response.json()
            if not isinstance(body, Mapping):
                raise TypeError("Google Text Search response must be an object")
            places = body.get("places", [])
            if not isinstance(places, Sequence) or isinstance(places, str | bytes):
                raise TypeError("Google Text Search places must be an array")
            normalized: list[AlongRouteProviderResult] = []
            seen: set[str] = set()
            for rank, raw in enumerate(places):
                result = _normalize_google_place(raw, rank)
                if result.suggestion.provider_ref in seen:
                    continue
                seen.add(result.suggestion.provider_ref)
                normalized.append(result)
            token = _optional_text(body.get("nextPageToken"))
            return AlongRouteProviderResponse(tuple(normalized), token)
        except (KeyError, TypeError, ValueError) as error:
            raise DestinationProviderError(
                "Google Places returned invalid Search Along Route data"
            ) from error


@dataclass(slots=True)
class AlongRouteSearchMetrics:
    provider_calls: int = 0
    latency_ms_total: int = 0
    provider_errors: int = 0
    rate_limited: int = 0
    results_returned: int = 0
    stale_responses_discarded: int = 0
    resolutions_succeeded: int = 0
    candidates_evaluated: int = 0
    candidates_within_budget: int = 0
    candidate_routing_errors: int = 0
    candidates_timed_out: int = 0

    def snapshot(self) -> dict[str, int]:
        return {item.name: int(getattr(self, item.name)) for item in fields(self)}


@dataclass(slots=True)
class _AlongRouteSession:
    route_id: str
    route_revision: int
    route_fingerprint: str
    query_revision: int
    query: str
    language: str
    full_search: bool
    results: dict[str, AlongRouteProviderResult] = field(default_factory=dict)
    page_tokens: dict[str, str] = field(default_factory=dict)
    updated_at: float = field(default_factory=time.monotonic)
    concluded_refs: set[str] = field(default_factory=set)
    last_response: AlongRouteSearchResponse | None = None
    completion: asyncio.Event = field(default_factory=asyncio.Event)


class AlongRouteSearchService:
    def __init__(
        self,
        *,
        provider: AlongRouteSearchProvider,
        max_geometry_points: int,
        max_encoded_polyline_chars: int,
        session_ttl_seconds: int,
        max_concurrency: int,
        requests_per_user_minute: int,
        minimum_query_characters: int,
        candidate_evaluation_limit: int = 10,
        candidate_evaluation_timeout_seconds: float = 20,
        recovery_call_limit: int = 2,
        minimum_eligible_results: int = 3,
    ) -> None:
        self._provider = provider
        self._max_geometry_points = max_geometry_points
        self._max_encoded_polyline_chars = max_encoded_polyline_chars
        self._session_ttl_seconds = session_ttl_seconds
        self._slots = asyncio.Semaphore(max_concurrency)
        self._limiter = PerUserRequestLimiter(requests_per_user_minute)
        self._minimum_query_characters = minimum_query_characters
        self._candidate_evaluation_limit = candidate_evaluation_limit
        self._candidate_evaluation_timeout_seconds = candidate_evaluation_timeout_seconds
        self._recovery_call_limit = recovery_call_limit
        self._minimum_eligible_results = minimum_eligible_results
        self._sessions: dict[tuple[str, str], _AlongRouteSession] = {}
        self._lock = asyncio.Lock()
        self.metrics = AlongRouteSearchMetrics()

    async def search(
        self,
        owner: str,
        request: AlongRouteSearchRequest,
        *,
        routing_provider: RoutingProvider | None = None,
    ) -> AlongRouteSearchResponse:
        _require_uuid4(request.session_id)
        query = request.query.strip()
        if len("".join(query.split())) < self._minimum_query_characters:
            raise ValueError("along-route query is shorter than configured minimum")
        if request.route is None:
            raise RouteRequiredError("a calculated route is required")
        route = request.route
        encoded, fingerprint = build_google_route_polyline(
            route,
            max_points=self._max_geometry_points,
            max_encoded_chars=self._max_encoded_polyline_chars,
        )
        await self._collect_expired()
        if not await self._limiter.acquire(owner):
            self.metrics.rate_limited += 1
            raise DestinationRateLimitedError(60)
        key = (owner, request.session_id)
        wait_for: asyncio.Event | None = None
        async with self._lock:
            session = self._sessions.get(key)
            if request.page_cursor is None:
                if session is not None and request.revision < session.query_revision:
                    raise StaleDestinationSelectionError("along-route query revision is stale")
                if (
                    session is not None
                    and request.revision == session.query_revision
                    and (
                        session.query != query
                        or session.route_fingerprint != fingerprint
                        or session.route_revision != route.route_revision
                    )
                ):
                    raise StaleDestinationSelectionError("query revision was reused")
                if (
                    session is not None
                    and request.revision == session.query_revision
                    and session.query == query
                    and session.route_fingerprint == fingerprint
                    and session.last_response is not None
                ):
                    return session.last_response
                if (
                    session is not None
                    and request.revision == session.query_revision
                    and session.query == query
                    and session.route_fingerprint == fingerprint
                ):
                    wait_for = session.completion
                else:
                    session = _AlongRouteSession(
                        route_id=route.route_id,
                        route_revision=route.route_revision,
                        route_fingerprint=fingerprint,
                        query_revision=request.revision,
                        query=query,
                        language=request.language,
                        full_search=request.full_search,
                    )
                    self._sessions[key] = session
                page_token = None
            else:
                if session is None or not _session_matches(session, request, fingerprint, query):
                    raise StaleDestinationSelectionError("pagination context is stale")
                page_token = session.page_tokens.get(request.page_cursor)
                if page_token is None:
                    raise StaleDestinationSelectionError("pagination cursor is stale")
        if wait_for is not None:
            await wait_for.wait()
            async with self._lock:
                current = self._sessions.get(key)
                if current is session and current.last_response is not None:
                    return current.last_response
            raise DestinationSearchUnavailableError("coalesced along-route search failed")
        query_intent = classify_waypoint_query(query)
        use_global_search = query_intent == "specific" or (
            query_intent == "ambiguous" and request.full_search
        )
        provider_polyline = None if use_global_search else encoded
        try:
            provider_response = await self._provider_search(
                query=query,
                language=request.language,
                encoded_polyline5=provider_polyline,
                page_token=page_token,
            )
        except Exception:
            session.completion.set()
            raise
        next_page_token = provider_response.next_page_token
        try:
            evaluated_results = await self._evaluate_candidates(
                provider_response.results,
                route=route,
                query_intent=query_intent,
                routing_provider=routing_provider,
                preserve_over_budget=use_global_search,
            )
        except Exception:
            session.completion.set()
            raise
        if (
            not use_global_search
            and _has_time_policy(route)
            and request.page_cursor is None
            and len(evaluated_results) < self._minimum_eligible_results
            and self._recovery_call_limit > 0
        ):
            known_refs = {item.suggestion.provider_ref for item in provider_response.results}
            expanded = list(evaluated_results)
            recovery_calls = 0
            while (
                len(expanded) < self._minimum_eligible_results
                and next_page_token is not None
                and recovery_calls < self._recovery_call_limit
            ):
                try:
                    page = await self._provider_search(
                        query=query,
                        language=request.language,
                        encoded_polyline5=provider_polyline,
                        page_token=next_page_token,
                    )
                    recovery_calls += 1
                    next_page_token = page.next_page_token
                    novel = tuple(
                        item
                        for item in page.results
                        if item.suggestion.provider_ref not in known_refs
                    )
                    known_refs.update(item.suggestion.provider_ref for item in novel)
                    expanded.extend(
                        await self._evaluate_candidates(
                            novel,
                            route=route,
                            query_intent=query_intent,
                            routing_provider=routing_provider,
                            preserve_over_budget=False,
                        )
                    )
                except (
                    DestinationRateLimitedError,
                    DestinationProviderError,
                    DestinationSearchUnavailableError,
                ):
                    break
            for recovery_polyline in build_google_route_recovery_polylines(
                route,
                max_points=self._max_geometry_points,
                max_encoded_chars=self._max_encoded_polyline_chars,
                segment_count=self._recovery_call_limit,
            ):
                if (
                    len(expanded) >= self._minimum_eligible_results
                    or recovery_calls >= self._recovery_call_limit
                ):
                    break
                try:
                    recovery = await self._provider_search(
                        query=query,
                        language=request.language,
                        encoded_polyline5=recovery_polyline,
                        page_token=None,
                    )
                    recovery_calls += 1
                    novel = tuple(
                        item
                        for item in recovery.results
                        if item.suggestion.provider_ref not in known_refs
                    )
                    known_refs.update(item.suggestion.provider_ref for item in novel)
                    expanded.extend(
                        await self._evaluate_candidates(
                            novel,
                            route=route,
                            query_intent=query_intent,
                            routing_provider=routing_provider,
                            preserve_over_budget=False,
                        )
                    )
                except (
                    DestinationRateLimitedError,
                    DestinationProviderError,
                    DestinationSearchUnavailableError,
                ):
                    break
            evaluated_results = tuple(
                sorted(
                    expanded,
                    key=lambda item: (
                        item.suggestion.marginal_added_duration_seconds
                        if item.suggestion.marginal_added_duration_seconds is not None
                        else float("inf"),
                        item.suggestion.provider_rank,
                    ),
                )
            )
        async with self._lock:
            current = self._sessions.get(key)
            if current is not session or not _session_matches(current, request, fingerprint, query):
                self.metrics.stale_responses_discarded += 1
                session.completion.set()
                raise StaleDestinationSelectionError("along-route response is stale")
            for result in evaluated_results:
                current.results.setdefault(result.suggestion.provider_ref, result)
            if request.page_cursor is not None:
                current.page_tokens.pop(request.page_cursor, None)
            next_cursor = None
            if next_page_token is not None:
                next_cursor = hashlib.sha256(
                    f"{fingerprint}:{query}:{request.revision}:{time.monotonic_ns()}".encode()
                ).hexdigest()[:32]
                current.page_tokens = {next_cursor: next_page_token}
            current.updated_at = time.monotonic()
        self.metrics.results_returned += len(evaluated_results)
        mode = (
            "global_specific"
            if use_global_search
            else "route_time_filtered"
            if _has_time_policy(route)
            else "route_biased"
        )
        response = AlongRouteSearchResponse(
            session_id=request.session_id,
            revision=request.revision,
            route_id=route.route_id,
            route_revision=route.route_revision,
            route_fingerprint=fingerprint,
            results=tuple(item.suggestion for item in evaluated_results),
            next_page_cursor=next_cursor,
            mode=mode,
            limitation=(
                "I candidati generici sono verificati con Valhalla rispetto al budget "
                "temporale cumulativo; Search Along Route non è un corridoio geometrico rigido."
                if not use_global_search and _has_time_policy(route)
                else "La ricerca specifica può includere luoghi oltre il budget; la conferma "
                "richiede comunque un'anteprima Valhalla."
                if use_global_search
                else ROUTE_BIAS_LIMITATION
            ),
        )
        async with self._lock:
            if self._sessions.get(key) is session and request.page_cursor is None:
                session.last_response = response
                session.completion.set()
        return response

    async def _provider_search(
        self,
        *,
        query: str,
        language: str,
        encoded_polyline5: str | None,
        page_token: str | None,
    ) -> AlongRouteProviderResponse:
        started = time.monotonic()
        self.metrics.provider_calls += 1
        try:
            async with self._slots:
                return await self._provider.search(
                    query=query,
                    language=language,
                    encoded_polyline5=encoded_polyline5,
                    page_token=page_token,
                )
        except DestinationRateLimitedError:
            self.metrics.rate_limited += 1
            raise
        except Exception:
            self.metrics.provider_errors += 1
            raise
        finally:
            self.metrics.latency_ms_total += round((time.monotonic() - started) * 1_000)

    async def _evaluate_candidates(
        self,
        results: tuple[AlongRouteProviderResult, ...],
        *,
        route: AlongRouteContext,
        query_intent: Literal["generic", "specific", "ambiguous"],
        routing_provider: RoutingProvider | None,
        preserve_over_budget: bool,
    ) -> tuple[AlongRouteProviderResult, ...]:
        results = tuple(
            item
            for item in results
            if not _matches_existing_route_point(item.selection.coordinate, route)
        )
        if not _has_time_policy(route):
            return tuple(
                replace(
                    item,
                    suggestion=replace(
                        item.suggestion,
                        search_intent=query_intent,
                        insertion_leg_index=(
                            route.insertion_leg_index
                            if route.insertion_leg_index is not None
                            else _nearest_insertion_leg_index(
                                route,
                                item.selection.coordinate,
                                max_points=self._max_geometry_points,
                            )
                        ),
                    ),
                )
                for item in results
            )
        if routing_provider is None:
            raise DestinationSearchUnavailableError(
                "Valhalla is required to evaluate along-route candidates"
            )
        baseline = route.baseline_duration_seconds
        current = route.current_duration_seconds
        maximum = route.maximum_total_added_duration_seconds
        assert baseline is not None and current is not None and maximum is not None
        if not results:
            return ()

        async def evaluate(item: AlongRouteProviderResult):
            waypoints = list(route.remaining_waypoints)
            insertion_index = (
                route.insertion_leg_index
                if route.insertion_leg_index is not None
                else _nearest_insertion_leg_index(
                    route,
                    item.selection.coordinate,
                    max_points=self._max_geometry_points,
                )
            )
            waypoints.insert(insertion_index, item.selection.coordinate)
            try:
                async with self._slots:
                    candidate = await routing_provider.route_with_waypoints(
                        WaypointRouteRequest(
                            origin=route.origin,
                            destination=route.final_destination,
                            waypoints=tuple(waypoints),
                            language="it-IT",
                            allow_highways=route.allow_highways,
                        )
                    )
            except (NoRouteError, RoutingUnavailableError, RoutingProviderError, ValueError):
                self.metrics.candidate_routing_errors += 1
                if preserve_over_budget:
                    return replace(
                        item,
                        suggestion=replace(
                            item.suggestion,
                            search_intent=query_intent,
                            insertion_leg_index=insertion_index,
                        ),
                    )
                return None
            self.metrics.candidates_evaluated += 1
            total_added = candidate.duration_seconds - baseline
            marginal_added = candidate.duration_seconds - current
            within = is_within_time_budget(
                candidate_duration_seconds=candidate.duration_seconds,
                baseline_duration_seconds=baseline,
                maximum_total_added_duration_seconds=maximum,
            )
            if within:
                self.metrics.candidates_within_budget += 1
            suggestion = replace(
                item.suggestion,
                search_intent=query_intent,
                marginal_added_duration_seconds=max(0.0, marginal_added),
                total_added_duration_seconds=max(0.0, total_added),
                within_time_budget=within,
                insertion_leg_index=insertion_index,
            )
            return replace(item, suggestion=suggestion)

        candidates = results[: self._candidate_evaluation_limit]
        tasks = {asyncio.create_task(evaluate(item)) for item in candidates}
        done, pending = await asyncio.wait(
            tasks,
            timeout=self._candidate_evaluation_timeout_seconds,
        )
        for task in pending:
            task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
        self.metrics.candidates_timed_out += len(pending)
        evaluated = tuple(
            sorted(
                (item for task in done if (item := task.result()) is not None),
                key=lambda item: item.suggestion.provider_rank,
            )
        )
        if results and not evaluated:
            raise DestinationSearchUnavailableError(
                "Valhalla could not evaluate any place candidate"
            )
        if preserve_over_budget:
            return evaluated
        eligible = tuple(item for item in evaluated if item.suggestion.within_time_budget)
        return tuple(
            sorted(
                eligible,
                key=lambda item: (
                    item.suggestion.marginal_added_duration_seconds
                    if item.suggestion.marginal_added_duration_seconds is not None
                    else float("inf"),
                    item.suggestion.provider_rank,
                ),
            )
        )

    async def resolve(
        self,
        *,
        owner: str,
        session_id: str,
        revision: int,
        route_id: str,
        route_revision: int,
        route_fingerprint: str,
        provider_ref: str,
        route: AlongRouteContext,
    ) -> tuple[ResolvedDestinationSelection, NavigationTarget]:
        _require_uuid4(session_id)
        _, current_fingerprint = build_google_route_polyline(
            route,
            max_points=self._max_geometry_points,
            max_encoded_chars=self._max_encoded_polyline_chars,
        )
        if current_fingerprint != route_fingerprint:
            raise StaleDestinationSelectionError("route changed after the search")
        async with self._lock:
            session = self._sessions.get((owner, session_id))
            if session is None or (
                session.query_revision != revision
                or session.route_id != route_id
                or session.route_revision != route_revision
                or session.route_fingerprint != route_fingerprint
            ):
                raise StaleDestinationSelectionError("along-route selection is stale")
            result = session.results.get(provider_ref)
            if result is None:
                raise StaleDestinationSelectionError("place was not returned by this search")
            first_resolution = provider_ref not in session.concluded_refs
            session.concluded_refs.add(provider_ref)
            session.updated_at = time.monotonic()
        if first_resolution:
            self.metrics.resolutions_succeeded += 1
        selection = result.selection
        return selection, NavigationTarget(selection.coordinate, selection.provider_ref)

    async def _collect_expired(self) -> None:
        now = time.monotonic()
        async with self._lock:
            for key in [
                key
                for key, session in self._sessions.items()
                if now - session.updated_at >= self._session_ttl_seconds
            ]:
                self._sessions.pop(key, None)


def build_google_route_polyline(
    context: AlongRouteContext,
    *,
    max_points: int,
    max_encoded_chars: int,
) -> tuple[str, str]:
    coordinates = _route_coordinates(context, max_points=max_points)
    encoded = encode_polyline5(coordinates)
    if len(encoded) > max_encoded_chars:
        raise InvalidRouteContextError("route geometry exceeds the configured encoded size")
    fingerprint = hashlib.sha256(
        json.dumps(
            {
                "route_id": context.route_id,
                "route_revision": context.route_revision,
                "progress": context.progress_shape_index,
                "leg": context.insertion_leg_index,
                "baseline_duration_seconds": context.baseline_duration_seconds,
                "current_duration_seconds": context.current_duration_seconds,
                "maximum_total_added_duration_seconds": (
                    context.maximum_total_added_duration_seconds
                ),
                "allow_highways": context.allow_highways,
                "polyline": encoded,
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode()
    ).hexdigest()
    return encoded, fingerprint


def build_google_route_recovery_polylines(
    context: AlongRouteContext,
    *,
    max_points: int,
    max_encoded_chars: int,
    segment_count: int = 2,
) -> tuple[str, ...]:
    coordinates = _route_coordinates(context, max_points=max_points)
    if len(coordinates) < 4 or segment_count < 2:
        return ()
    segment_size = max(2, (len(coordinates) + segment_count - 1) // segment_count)
    result: list[str] = []
    start = 0
    while start < len(coordinates) - 1 and len(result) < segment_count:
        end = min(len(coordinates), start + segment_size + 1)
        encoded = encode_polyline5(coordinates[start:end])
        if len(encoded) <= max_encoded_chars:
            result.append(encoded)
        start = end - 1
    return tuple(result)


def _route_coordinates(
    context: AlongRouteContext,
    *,
    max_points: int,
) -> tuple[Coordinate, ...]:
    if not context.route_id.strip() or context.route_revision < 0:
        raise InvalidRouteContextError("route identity or revision is invalid")
    if not context.legs:
        raise RouteRequiredError("route geometry is missing")
    if len(context.legs) != len(context.remaining_waypoints) + 1:
        raise InvalidRouteContextError("route legs do not match remaining waypoints")
    route_points = (context.origin, *context.remaining_waypoints, context.final_destination)
    if any(
        not isfinite(point.latitude)
        or not isfinite(point.longitude)
        or not -90 <= point.latitude <= 90
        or not -180 <= point.longitude <= 180
        for point in route_points
    ):
        raise InvalidRouteContextError("route waypoints contain an invalid coordinate")
    decoded: list[tuple[Coordinate, ...]] = []
    consumed = 0
    try:
        for leg in context.legs:
            points = decode_polyline6(
                leg.encoded_polyline6,
                max_points=max_points - consumed,
            )
            consumed += len(points)
            decoded.append(points)
    except (RoutingProviderError, ValueError) as error:
        raise InvalidRouteContextError("route geometry is invalid") from error
    for index, points in enumerate(decoded):
        if (
            _distance_meters(points[0], route_points[index]) > 250
            or _distance_meters(points[-1], route_points[index + 1]) > 250
        ):
            raise InvalidRouteContextError("route geometry does not match its waypoints")
    leg_index = context.insertion_leg_index
    if leg_index is not None:
        if leg_index not in range(len(decoded)):
            raise InvalidRouteContextError("insertion leg is outside the remaining route")
        coordinates = list(decoded[leg_index])
    else:
        coordinates = []
        for points in decoded:
            coordinates.extend(
                points
                if not coordinates
                else points[1:]
                if points[0] == coordinates[-1]
                else points
            )
    if context.progress_shape_index is not None:
        if leg_index is not None:
            raise InvalidRouteContextError("progress index and explicit leg cannot be combined")
        if context.progress_shape_index not in range(len(coordinates) - 1):
            raise InvalidRouteContextError("route progress is outside the route geometry")
        coordinates = coordinates[context.progress_shape_index :]
    if len(coordinates) < 2:
        raise InvalidRouteContextError("remaining route geometry is empty")
    return tuple(coordinates)


def _has_time_policy(context: AlongRouteContext) -> bool:
    return context.baseline_duration_seconds is not None


def is_within_time_budget(
    *,
    candidate_duration_seconds: float,
    baseline_duration_seconds: float,
    maximum_total_added_duration_seconds: float,
) -> bool:
    values = (
        candidate_duration_seconds,
        baseline_duration_seconds,
        maximum_total_added_duration_seconds,
    )
    if any(not isfinite(value) or value < 0 for value in values):
        raise ValueError("time budget inputs must be finite and non-negative")
    return candidate_duration_seconds <= (
        baseline_duration_seconds + maximum_total_added_duration_seconds
    )


_GENERIC_WAYPOINT_TERMS = frozenset(
    {
        "alimentari",
        "bancomat",
        "bar",
        "benzinaio",
        "caffe",
        "caffetteria",
        "edicola",
        "farmacia",
        "farmacie",
        "hotel",
        "ospedale",
        "parcheggio",
        "pizzeria",
        "pizzerie",
        "ristorante",
        "ristoranti",
        "supermercato",
        "supermercati",
        "tabaccheria",
        "tabaccherie",
    }
)
_ADDRESS_TERMS = frozenset(
    {"via", "viale", "piazza", "corso", "strada", "vicolo", "largo", "localita"}
)
_GENERIC_QUALIFIERS = frozenset(
    {"aperto", "aperta", "adesso", "vegetariano", "vegetariana", "vegano", "vegana", "24h"}
)


def classify_waypoint_query(query: str) -> Literal["generic", "specific", "ambiguous"]:
    """Classify without provider calls; the result controls route filtering semantics."""
    plain = "".join(
        character
        for character in unicodedata.normalize("NFKD", query)
        if not unicodedata.combining(character)
    )
    normalized = "".join(
        character.lower() if character.isalnum() else " " for character in plain.strip()
    )
    tokens = tuple(token for token in normalized.split() if token)
    token_set = set(tokens)
    if any(token.isdigit() for token in tokens) and token_set & _ADDRESS_TERMS:
        return "specific"
    if "," in query or (token_set & _ADDRESS_TERMS and len(tokens) >= 3):
        return "specific"
    if "a" in token_set and token_set & _GENERIC_WAYPOINT_TERMS:
        return "specific"
    categories = token_set & _GENERIC_WAYPOINT_TERMS
    if categories and token_set <= categories | _GENERIC_QUALIFIERS:
        return "generic"
    return "ambiguous"


def _normalize_google_place(raw: Any, rank: int) -> AlongRouteProviderResult:
    if not isinstance(raw, Mapping):
        raise TypeError("Google place must be an object")
    place_id = _required_text(raw.get("id"), "Google place has no id")
    display = raw.get("displayName")
    if not isinstance(display, Mapping):
        raise TypeError("Google place has no display name")
    title = _required_text(display.get("text"), "Google place has no display name")
    address = _optional_text(raw.get("formattedAddress"))
    location = raw.get("location")
    if not isinstance(location, Mapping):
        raise TypeError("Google place has no location")
    latitude = float(location["latitude"])
    longitude = float(location["longitude"])
    if (
        not isfinite(latitude)
        or not isfinite(longitude)
        or not -90 <= latitude <= 90
        or not -180 <= longitude <= 180
    ):
        raise ValueError("Google place coordinate is invalid")
    coordinate = Coordinate(latitude, longitude)
    types = (
        {value for item in raw.get("types", []) if (value := _optional_text(item)) is not None}
        if isinstance(raw.get("types", []), Sequence)
        else set()
    )
    kind = (
        "business"
        if types & {"point_of_interest", "establishment"}
        else (
            "address"
            if types & {"street_address", "premise", "route", "intersection"}
            else ("locality" if types & {"locality", "postal_town"} else "unknown")
        )
    )
    attributions = ["Google Maps"]
    raw_attributions = raw.get("attributions", [])
    if isinstance(raw_attributions, Sequence) and not isinstance(raw_attributions, str | bytes):
        for attribution in raw_attributions:
            if isinstance(attribution, Mapping):
                label = _optional_text(attribution.get("provider"))
                if label and label not in attributions:
                    attributions.append(label)
    suggestion = DestinationSuggestion(
        id=f"google_places_new:{place_id}",
        provider="google_places_new",
        provider_ref=place_id,
        kind=kind,
        title=title,
        subtitle=address,
        address_preview=address,
        distance_meters=None,
        provider_rank=rank,
        requires_resolution=False,
        attribution="Google Maps",
    )
    selection = ResolvedDestinationSelection(
        provider="google_places_new",
        provider_ref=place_id,
        formatted_address=address,
        address_components=(),
        normalized_address=NormalizedAddress(),
        coordinate=coordinate,
        kind=kind,
        attribution=tuple(attributions),
        field_sources=(
            ("formatted_address", "google_places_new"),
            ("location", "google_places_new"),
        ),
    )
    return AlongRouteProviderResult(suggestion, selection)


def _session_matches(
    session: _AlongRouteSession,
    request: AlongRouteSearchRequest,
    fingerprint: str,
    query: str,
) -> bool:
    route = request.route
    return route is not None and (
        session.query_revision == request.revision
        and session.query == query
        and session.language == request.language
        and session.full_search == request.full_search
        and session.route_id == route.route_id
        and session.route_revision == route.route_revision
        and session.route_fingerprint == fingerprint
    )


def _require_uuid4(value: str) -> None:
    try:
        parsed = UUID(value)
    except ValueError as error:
        raise ValueError("search session id must be UUID v4") from error
    if parsed.version != 4 or str(parsed) != value.lower():
        raise ValueError("search session id must be canonical UUID v4")


def _optional_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _required_text(value: Any, message: str) -> str:
    text = _optional_text(value)
    if text is None:
        raise ValueError(message)
    return text


def _distance_meters(first: Coordinate, second: Coordinate) -> float:
    lat1, lat2 = radians(first.latitude), radians(second.latitude)
    delta_lat = lat2 - lat1
    delta_lon = radians(second.longitude - first.longitude)
    value = sin(delta_lat / 2) ** 2 + cos(lat1) * cos(lat2) * sin(delta_lon / 2) ** 2
    return 2 * 6_371_000 * asin(sqrt(value))


def _matches_existing_route_point(
    candidate: Coordinate,
    context: AlongRouteContext,
) -> bool:
    return any(
        _distance_meters(candidate, point) <= EXISTING_WAYPOINT_DUPLICATE_RADIUS_METERS
        for point in (
            context.origin,
            *context.remaining_waypoints,
            context.final_destination,
        )
    )


def _nearest_insertion_leg_index(
    context: AlongRouteContext,
    candidate: Coordinate,
    *,
    max_points: int,
) -> int:
    """Return the route leg containing the closest geometry to the candidate."""
    best_index = 0
    best_distance = float("inf")
    remaining_points = max_points
    for index, leg in enumerate(context.legs):
        points = decode_polyline6(leg.encoded_polyline6, max_points=remaining_points)
        remaining_points -= len(points)
        distance = min(
            _point_to_segment_distance_meters(candidate, start, end)
            for start, end in zip(points, points[1:], strict=False)
        )
        if distance < best_distance:
            best_index = index
            best_distance = distance
    return best_index


def _point_to_segment_distance_meters(
    point: Coordinate,
    start: Coordinate,
    end: Coordinate,
) -> float:
    reference_latitude = radians(point.latitude)
    meters_per_degree_latitude = 111_320.0
    meters_per_degree_longitude = meters_per_degree_latitude * cos(reference_latitude)
    start_x = (start.longitude - point.longitude) * meters_per_degree_longitude
    start_y = (start.latitude - point.latitude) * meters_per_degree_latitude
    end_x = (end.longitude - point.longitude) * meters_per_degree_longitude
    end_y = (end.latitude - point.latitude) * meters_per_degree_latitude
    segment_x = end_x - start_x
    segment_y = end_y - start_y
    squared_length = segment_x * segment_x + segment_y * segment_y
    if squared_length == 0:
        return sqrt(start_x * start_x + start_y * start_y)
    projection = max(
        0.0,
        min(1.0, -(start_x * segment_x + start_y * segment_y) / squared_length),
    )
    closest_x = start_x + projection * segment_x
    closest_y = start_y + projection * segment_y
    return sqrt(closest_x * closest_x + closest_y * closest_y)


def _path_length(coordinates: list[Coordinate]) -> float:
    return sum(_distance_meters(a, b) for a, b in zip(coordinates, coordinates[1:], strict=False))
