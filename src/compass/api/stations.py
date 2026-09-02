from dataclasses import asdict
from datetime import UTC, datetime
from decimal import Decimal
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Path, Query
from fastapi.responses import JSONResponse
from pydantic import Field, model_validator
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from compass.api.contracts import ErrorResponse, StrictModel, error_response
from compass.api.ranking import CurrentCngPriceResponse, OpeningHoursEvaluationResponse
from compass.api.routes import (
    BaseRouteRequest,
    CoordinateRequest,
    ManeuverResponse,
    NavigationTimingResponse,
    RouteGeometry,
    _navigation_timing_response,
)
from compass.config import Settings, get_api_settings
from compass.db import get_session
from compass.navigation.domain import build_navigation_timing, fuel_stop_arrival_times
from compass.predictive.domain import MAX_CNG_ITINERARY_STOPS
from compass.ranking.domain import CurrentCngPrice
from compass.ranking.opening_hours import evaluate_opening_hours
from compass.ranking.service import evaluate_price
from compass.routing.dependencies import get_routing_provider
from compass.routing.domain import (
    Coordinate,
    NoRouteError,
    RoutingProvider,
    RoutingProviderError,
    RoutingUnavailableError,
    WaypointRouteRequest,
)
from compass.stations.domain import StationDetail
from compass.stations.repository import load_station_detail, load_station_route_points
from compass.traffic.dependencies import get_traffic_route_refresher
from compass.traffic.route_refresh import TrafficRouteRefresher
from compass.traffic.routing import refresh_waypoint_route_traffic

router = APIRouter(prefix="/api/v1", tags=["stations"])
MimitStationId = Annotated[str, Path(pattern=r"^[0-9]{1,32}$")]


class StationLocationResponse(StrictModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    source: str | None


class StationOsmResponse(StrictModel):
    osm_type: str
    osm_id: int
    name: str | None
    opening_hours: str | None
    phone: str | None
    brand: str | None
    operator: str | None
    source_observed_at: datetime | None
    match_method: str
    confidence: float = Field(ge=0, le=1)
    distance_meters: float | None = Field(default=None, ge=0)
    is_manual: bool


class StationOpeningAtArrivalResponse(StrictModel):
    status: Literal["not_requested", "evaluated", "location_unavailable"]
    evaluation: OpeningHoursEvaluationResponse | None


class StationCurrentCngPriceResponse(CurrentCngPriceResponse):
    fuel_type: Literal["cng"] = "cng"


class StationDetailResponse(StrictModel):
    mimit_station_id: str
    name: str | None
    address: str | None
    municipality: str | None
    province: str | None
    brand: str | None
    manager: str | None
    station_type: str | None
    is_active: bool
    location: StationLocationResponse | None
    source_observed_at: datetime | None
    updated_at: datetime
    price_reference_at: datetime
    current_cng_prices: list[StationCurrentCngPriceResponse]
    osm: StationOsmResponse | None
    opening_at_arrival: StationOpeningAtArrivalResponse


class RouteWithCngStopRequest(BaseRouteRequest):
    mimit_station_id: str = Field(pattern=r"^[0-9]{1,32}$")


class SelectedCngStopResponse(StrictModel):
    mimit_station_id: str
    name: str | None
    municipality: str | None
    province: str | None
    location: CoordinateRequest
    expected_arrival_at: datetime | None = None
    dwell_time_seconds: int = Field(default=20 * 60, ge=0)


class RouteLegResponse(StrictModel):
    kind: Literal["origin_to_cng_station", "cng_station_to_destination"]
    origin: CoordinateRequest
    destination: CoordinateRequest
    distance_meters: float = Field(ge=0)
    duration_seconds: float = Field(ge=0)
    geometry: RouteGeometry
    maneuvers: list[ManeuverResponse]


class RouteWithCngStopResponse(StrictModel):
    selected_stop: SelectedCngStopResponse
    distance_meters: float = Field(ge=0)
    duration_seconds: float = Field(ge=0)
    legs: list[RouteLegResponse] = Field(min_length=2, max_length=2)
    provider: Literal["valhalla"]
    navigation: NavigationTimingResponse


class RouteWithCngItineraryRequest(BaseRouteRequest):
    mimit_station_ids: list[str] = Field(
        min_length=1,
        max_length=MAX_CNG_ITINERARY_STOPS,
    )
    effective_cng_range_km: float = Field(gt=0, le=2000)
    estimated_remaining_cng_range_km: float = Field(gt=0, le=2000)
    reserve_cng_range_km: float = Field(ge=0, lt=2000)

    @model_validator(mode="after")
    def validate_itinerary_range(self) -> "RouteWithCngItineraryRequest":
        if len(set(self.mimit_station_ids)) != len(self.mimit_station_ids):
            raise ValueError("mimit_station_ids must not contain duplicates")
        if any(
            not station_id.isascii()
            or not station_id.isdigit()
            or len(station_id) > 32
            for station_id in self.mimit_station_ids
        ):
            raise ValueError("mimit_station_ids must contain official numeric IDs")
        if self.estimated_remaining_cng_range_km > self.effective_cng_range_km:
            raise ValueError("estimated remaining range must not exceed effective range")
        if self.reserve_cng_range_km >= self.estimated_remaining_cng_range_km:
            raise ValueError("reserve range must be lower than estimated remaining range")
        if self.reserve_cng_range_km >= self.effective_cng_range_km:
            raise ValueError("reserve range must be lower than effective range")
        return self


class CngItineraryStopResponse(SelectedCngStopResponse):
    sequence: int = Field(gt=0)


class CngItineraryRouteLegResponse(StrictModel):
    sequence: int = Field(gt=0)
    kind: Literal[
        "origin_to_cng_station",
        "cng_station_to_cng_station",
        "cng_station_to_destination",
    ]
    origin: CoordinateRequest
    destination: CoordinateRequest
    distance_meters: float = Field(ge=0)
    duration_seconds: float = Field(ge=0)
    geometry: RouteGeometry
    maneuvers: list[ManeuverResponse]
    available_range_at_departure_km: float = Field(gt=0)
    estimated_remaining_range_at_arrival_km: float = Field(ge=0)
    reserve_margin_at_arrival_km: float = Field(ge=0)


class RouteWithCngItineraryResponse(StrictModel):
    selected_stops: list[CngItineraryStopResponse] = Field(
        min_length=1,
        max_length=MAX_CNG_ITINERARY_STOPS,
    )
    distance_meters: float = Field(ge=0)
    duration_seconds: float = Field(ge=0)
    legs: list[CngItineraryRouteLegResponse] = Field(min_length=2, max_length=33)
    provider: Literal["valhalla"]
    range_validation: Literal["all_legs_preserve_reserve"] = "all_legs_preserve_reserve"
    navigation: NavigationTimingResponse


@router.get(
    "/cng/stations/{mimit_station_id}",
    response_model=StationDetailResponse,
    responses={
        404: {"model": ErrorResponse, "description": "Station not found."},
        422: {"model": ErrorResponse, "description": "Invalid station ID or arrival time."},
        503: {"model": ErrorResponse, "description": "Station database unavailable."},
    },
)
async def station_detail(
    mimit_station_id: MimitStationId,
    session: Annotated[Session, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_api_settings)],
    arrival_at: Annotated[
        datetime | None,
        Query(description="Optional timezone-aware arrival instant for opening evaluation."),
    ] = None,
) -> StationDetailResponse | JSONResponse:
    if arrival_at is not None and (arrival_at.tzinfo is None or arrival_at.utcoffset() is None):
        return error_response(422, "invalid_request", "arrival_at must include a UTC offset.")
    try:
        station = load_station_detail(session, mimit_station_id)
    except SQLAlchemyError:
        return error_response(503, "database_unavailable", "The station database is unavailable.")
    if station is None:
        return error_response(404, "station_not_found", "The CNG station was not found.")
    return _station_detail_response(station, arrival_at=arrival_at, settings=settings)


@router.post(
    "/routes/with-cng-stop",
    response_model=RouteWithCngStopResponse,
    responses={
        404: {"model": ErrorResponse, "description": "Station not found."},
        409: {"model": ErrorResponse, "description": "Station cannot be routed."},
        422: {"model": ErrorResponse, "description": "Invalid request or no route."},
        502: {"model": ErrorResponse, "description": "Invalid routing provider response."},
        503: {"model": ErrorResponse, "description": "Database or routing unavailable."},
    },
)
async def route_with_cng_stop(
    request: RouteWithCngStopRequest,
    session: Annotated[Session, Depends(get_session)],
    provider: Annotated[RoutingProvider, Depends(get_routing_provider)],
    traffic_refresher: Annotated[
        TrafficRouteRefresher, Depends(get_traffic_route_refresher)
    ],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> RouteWithCngStopResponse | JSONResponse:
    try:
        station = load_station_detail(session, request.mimit_station_id)
    except SQLAlchemyError:
        return error_response(503, "database_unavailable", "The station database is unavailable.")
    if station is None:
        return error_response(404, "station_not_found", "The CNG station was not found.")
    if not station.is_active:
        return error_response(409, "station_inactive", "The CNG station is inactive.")
    if station.latitude is None or station.longitude is None:
        return error_response(
            409,
            "station_location_unavailable",
            "The CNG station has no usable location.",
        )

    origin = Coordinate(request.origin.latitude, request.origin.longitude)
    destination = Coordinate(request.destination.latitude, request.destination.longitude)
    stop = Coordinate(station.latitude, station.longitude)
    route_request = WaypointRouteRequest(
        origin=origin,
        destination=destination,
        waypoints=(stop,),
        costing=request.costing,
        language=request.language or settings.valhalla_route_language,
        departure_at=request.departure_at,
    )
    try:
        route = await provider.route_with_waypoints(route_request)
    except NoRouteError:
        return error_response(
            422, "route_not_found", "No route was found through the selected station."
        )
    except RoutingUnavailableError:
        return error_response(503, "routing_unavailable", "The routing service is unavailable.")
    except RoutingProviderError:
        return error_response(
            502,
            "routing_provider_error",
            "The routing service returned an invalid response.",
        )

    route = await refresh_waypoint_route_traffic(
        route=route,
        request=route_request,
        provider=provider,
        refresher=traffic_refresher,
        current_departure_tolerance_seconds=(
            settings.traffic_route_refresh_min_interval_seconds
        ),
    )

    departure_at = request.departure_at or datetime.now(UTC)
    stop_arrival_at = fuel_stop_arrival_times(
        departure_at=departure_at,
        leg_durations_seconds=(leg.duration_seconds for leg in route.legs),
        stop_count=1,
    )[0]
    navigation = build_navigation_timing(
        encoded_polylines=(leg.encoded_polyline for leg in route.legs),
        driving_duration_seconds=route.duration_seconds,
        fuel_stop_ids=(station.mimit_station_id,),
        departure_at=departure_at,
    )
    coordinates = (origin, stop, destination)
    kinds = ("origin_to_cng_station", "cng_station_to_destination")
    legs = [
        RouteLegResponse(
            kind=kinds[index],
            origin=CoordinateRequest(
                latitude=coordinates[index].latitude,
                longitude=coordinates[index].longitude,
            ),
            destination=CoordinateRequest(
                latitude=coordinates[index + 1].latitude,
                longitude=coordinates[index + 1].longitude,
            ),
            distance_meters=leg.distance_meters,
            duration_seconds=leg.duration_seconds,
            geometry=RouteGeometry(encoded_polyline=leg.encoded_polyline),
            maneuvers=[
                ManeuverResponse.model_validate(asdict(maneuver)) for maneuver in leg.maneuvers
            ],
        )
        for index, leg in enumerate(route.legs)
    ]
    return RouteWithCngStopResponse(
        selected_stop=SelectedCngStopResponse(
            mimit_station_id=station.mimit_station_id,
            name=station.name,
            municipality=station.municipality,
            province=station.province,
            location=CoordinateRequest(latitude=stop.latitude, longitude=stop.longitude),
            expected_arrival_at=stop_arrival_at,
        ),
        distance_meters=route.distance_meters,
        duration_seconds=route.duration_seconds,
        legs=legs,
        provider="valhalla",
        navigation=_navigation_timing_response(navigation),
    )


@router.post(
    "/routes/with-cng-itinerary",
    response_model=RouteWithCngItineraryResponse,
    responses={
        404: {"model": ErrorResponse, "description": "One or more stations were not found."},
        409: {"model": ErrorResponse, "description": "The itinerary is unusable or unsafe."},
        422: {"model": ErrorResponse, "description": "Invalid request or no route."},
        502: {"model": ErrorResponse, "description": "Invalid routing provider response."},
        503: {"model": ErrorResponse, "description": "Database or routing unavailable."},
    },
)
async def route_with_cng_itinerary(
    request: RouteWithCngItineraryRequest,
    session: Annotated[Session, Depends(get_session)],
    provider: Annotated[RoutingProvider, Depends(get_routing_provider)],
    traffic_refresher: Annotated[
        TrafficRouteRefresher, Depends(get_traffic_route_refresher)
    ],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> RouteWithCngItineraryResponse | JSONResponse:
    station_ids = tuple(request.mimit_station_ids)
    try:
        stations_by_id = load_station_route_points(session, station_ids)
    except SQLAlchemyError:
        return error_response(503, "database_unavailable", "The station database is unavailable.")
    missing_ids = [station_id for station_id in station_ids if station_id not in stations_by_id]
    if missing_ids:
        return error_response(
            404,
            "station_not_found",
            "One or more CNG stations in the itinerary were not found.",
        )
    stations = [stations_by_id[station_id] for station_id in station_ids]
    if any(not station.is_active for station in stations):
        return error_response(
            409,
            "station_inactive",
            "One or more CNG stations in the itinerary are inactive.",
        )
    if any(station.latitude is None or station.longitude is None for station in stations):
        return error_response(
            409,
            "station_location_unavailable",
            "One or more CNG stations in the itinerary have no usable location.",
        )

    origin = Coordinate(request.origin.latitude, request.origin.longitude)
    destination = Coordinate(request.destination.latitude, request.destination.longitude)
    stop_coordinates = tuple(
        Coordinate(float(station.latitude), float(station.longitude)) for station in stations
    )
    route_request = WaypointRouteRequest(
        origin=origin,
        destination=destination,
        waypoints=stop_coordinates,
        costing=request.costing,
        language=request.language or settings.valhalla_route_language,
        departure_at=request.departure_at,
    )
    try:
        route = await provider.route_with_waypoints(route_request)
    except NoRouteError:
        return error_response(
            422,
            "route_not_found",
            "No route was found through the complete CNG itinerary.",
        )
    except RoutingUnavailableError:
        return error_response(503, "routing_unavailable", "The routing service is unavailable.")
    except RoutingProviderError:
        return error_response(
            502,
            "routing_provider_error",
            "The routing service returned an invalid response.",
        )

    route = await refresh_waypoint_route_traffic(
        route=route,
        request=route_request,
        provider=provider,
        refresher=traffic_refresher,
        current_departure_tolerance_seconds=(
            settings.traffic_route_refresh_min_interval_seconds
        ),
    )

    coordinates = (origin, *stop_coordinates, destination)
    response_legs: list[CngItineraryRouteLegResponse] = []
    for index, leg in enumerate(route.legs):
        available_range_km = (
            request.estimated_remaining_cng_range_km
            if index == 0
            else request.effective_cng_range_km
        )
        remaining_range_km = available_range_km - leg.distance_meters / 1_000
        reserve_margin_km = remaining_range_km - request.reserve_cng_range_km
        if reserve_margin_km < 0:
            return error_response(
                409,
                "cng_itinerary_out_of_range",
                "At least one routed leg cannot preserve the requested CNG reserve.",
            )
        kind: Literal[
            "origin_to_cng_station",
            "cng_station_to_cng_station",
            "cng_station_to_destination",
        ]
        if index == 0:
            kind = "origin_to_cng_station"
        elif index == len(route.legs) - 1:
            kind = "cng_station_to_destination"
        else:
            kind = "cng_station_to_cng_station"
        response_legs.append(
            CngItineraryRouteLegResponse(
                sequence=index + 1,
                kind=kind,
                origin=CoordinateRequest(
                    latitude=coordinates[index].latitude,
                    longitude=coordinates[index].longitude,
                ),
                destination=CoordinateRequest(
                    latitude=coordinates[index + 1].latitude,
                    longitude=coordinates[index + 1].longitude,
                ),
                distance_meters=leg.distance_meters,
                duration_seconds=leg.duration_seconds,
                geometry=RouteGeometry(encoded_polyline=leg.encoded_polyline),
                maneuvers=[
                    ManeuverResponse.model_validate(asdict(maneuver))
                    for maneuver in leg.maneuvers
                ],
                available_range_at_departure_km=available_range_km,
                estimated_remaining_range_at_arrival_km=remaining_range_km,
                reserve_margin_at_arrival_km=reserve_margin_km,
            )
        )

    # Valhalla serializes the trip summary and every leg summary independently.
    # Their rounded totals can diverge by several metres once an itinerary has
    # many stops, so expose the authoritative sum of the legs that were actually
    # range-validated above.
    distance_meters = sum(leg.distance_meters for leg in route.legs)
    duration_seconds = sum(leg.duration_seconds for leg in route.legs)
    departure_at = request.departure_at or datetime.now(UTC)
    stop_arrivals = fuel_stop_arrival_times(
        departure_at=departure_at,
        leg_durations_seconds=(leg.duration_seconds for leg in route.legs),
        stop_count=len(stations),
    )
    navigation = build_navigation_timing(
        encoded_polylines=(leg.encoded_polyline for leg in route.legs),
        driving_duration_seconds=duration_seconds,
        fuel_stop_ids=station_ids,
        departure_at=departure_at,
    )
    return RouteWithCngItineraryResponse(
        selected_stops=[
            CngItineraryStopResponse(
                sequence=index,
                mimit_station_id=station.mimit_station_id,
                name=station.name,
                municipality=station.municipality,
                province=station.province,
                location=CoordinateRequest(
                    latitude=stop.latitude,
                    longitude=stop.longitude,
                ),
                expected_arrival_at=stop_arrivals[index - 1],
            )
            for index, (station, stop) in enumerate(
                zip(stations, stop_coordinates, strict=True),
                start=1,
            )
        ],
        distance_meters=distance_meters,
        duration_seconds=duration_seconds,
        legs=response_legs,
        provider="valhalla",
        navigation=_navigation_timing_response(navigation),
    )


def _station_detail_response(
    station: StationDetail, *, arrival_at: datetime | None, settings: Settings
) -> StationDetailResponse:
    reference_at = arrival_at or datetime.now(UTC)
    prices = []
    for price in station.current_cng_prices:
        evaluated = evaluate_price(
            CurrentCngPrice(
                unit_price=Decimal(price.unit_price),
                currency=price.currency,
                unit=price.unit,
                service_mode=price.service_mode,
                observed_at=price.observed_at,
                ingested_at=price.ingested_at,
                source_name=price.source_name,
            ),
            eta=reference_at,
            freshness_seconds=settings.cng_price_freshness_hours * 3600,
        )
        assert evaluated is not None
        prices.append(
            StationCurrentCngPriceResponse.model_validate(
                {
                    **asdict(evaluated),
                    "unit_price": float(evaluated.unit_price),
                    "fuel_type": "cng",
                }
            )
        )

    osm = station.osm
    opening_status: Literal["not_requested", "evaluated", "location_unavailable"]
    opening_evaluation = None
    if arrival_at is None:
        opening_status = "not_requested"
    elif station.latitude is None or station.longitude is None:
        opening_status = "location_unavailable"
    else:
        opening_status = "evaluated"
        evaluated_opening = evaluate_opening_hours(
            osm.opening_hours if osm is not None else None,
            eta=arrival_at,
            latitude=station.latitude,
            longitude=station.longitude,
            timezone_name=settings.opening_hours_timezone,
            country="IT",
            source_confidence=osm.confidence if osm is not None else None,
        )
        opening_evaluation = OpeningHoursEvaluationResponse.model_validate(
            asdict(evaluated_opening)
        )

    return StationDetailResponse(
        mimit_station_id=station.mimit_station_id,
        name=station.name,
        address=station.address,
        municipality=station.municipality,
        province=station.province,
        brand=station.brand,
        manager=station.manager,
        station_type=station.station_type,
        is_active=station.is_active,
        location=(
            StationLocationResponse(
                latitude=station.latitude,
                longitude=station.longitude,
                source=station.location_source,
            )
            if station.latitude is not None and station.longitude is not None
            else None
        ),
        source_observed_at=station.source_observed_at,
        updated_at=station.updated_at,
        price_reference_at=reference_at,
        current_cng_prices=prices,
        osm=StationOsmResponse.model_validate(asdict(osm)) if osm is not None else None,
        opening_at_arrival=StationOpeningAtArrivalResponse(
            status=opening_status,
            evaluation=opening_evaluation,
        ),
    )
