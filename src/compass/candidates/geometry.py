from dataclasses import replace

from compass.routing.domain import BaseRoute, Coordinate, RoutingProviderError, WaypointRoute


def decode_polyline6(encoded: str, *, max_points: int) -> tuple[Coordinate, ...]:
    """Decode a Valhalla polyline6 into validated WGS84 coordinates."""
    if not encoded:
        raise RoutingProviderError("Route geometry is empty")
    if max_points < 2:
        raise ValueError("max_points must be at least two")

    coordinates: list[Coordinate] = []
    latitude = 0
    longitude = 0
    index = 0
    decoded_point_count = 0
    while index < len(encoded):
        latitude_delta, index = _decode_value(encoded, index)
        longitude_delta, index = _decode_value(encoded, index)
        latitude += latitude_delta
        longitude += longitude_delta
        decoded_point_count += 1
        if decoded_point_count > max_points:
            raise RoutingProviderError("Route geometry exceeds the configured point limit")
        coordinate = Coordinate(latitude=latitude / 1_000_000, longitude=longitude / 1_000_000)
        if not (-90 <= coordinate.latitude <= 90 and -180 <= coordinate.longitude <= 180):
            raise RoutingProviderError("Route geometry contains an invalid coordinate")
        if not coordinates or coordinate != coordinates[-1]:
            coordinates.append(coordinate)

    if len(coordinates) < 2:
        raise RoutingProviderError("Route geometry must contain at least two distinct points")
    return tuple(coordinates)


def route_linestring_wkt(coordinates: tuple[Coordinate, ...]) -> str:
    if len(coordinates) < 2:
        raise ValueError("A route LineString requires at least two coordinates")
    points = ",".join(
        f"{coordinate.longitude:.6f} {coordinate.latitude:.6f}" for coordinate in coordinates
    )
    return f"LINESTRING({points})"


def encode_polyline5(coordinates: tuple[Coordinate, ...]) -> str:
    """Encode validated WGS84 coordinates using Google's E5 polyline format."""
    if len(coordinates) < 2:
        raise ValueError("A Google route polyline requires at least two coordinates")
    encoded: list[str] = []
    previous_latitude = 0
    previous_longitude = 0
    for coordinate in coordinates:
        latitude = round(coordinate.latitude * 100_000)
        longitude = round(coordinate.longitude * 100_000)
        encoded.append(_encode_value(latitude - previous_latitude))
        encoded.append(_encode_value(longitude - previous_longitude))
        previous_latitude = latitude
        previous_longitude = longitude
    return "".join(encoded)


def encode_polyline6(coordinates: tuple[Coordinate, ...]) -> str:
    """Encode validated WGS84 coordinates using Valhalla's E6 format."""
    if len(coordinates) < 2:
        raise ValueError("A Valhalla route polyline requires at least two coordinates")
    encoded: list[str] = []
    previous_latitude = 0
    previous_longitude = 0
    for coordinate in coordinates:
        latitude = round(coordinate.latitude * 1_000_000)
        longitude = round(coordinate.longitude * 1_000_000)
        encoded.append(_encode_value(latitude - previous_latitude))
        encoded.append(_encode_value(longitude - previous_longitude))
        previous_latitude = latitude
        previous_longitude = longitude
    return "".join(encoded)


def waypoint_route_as_base_route(
    route: WaypointRoute,
    *,
    max_points: int,
) -> BaseRoute:
    """Join a routed waypoint itinerary without losing its selected geometry."""
    coordinates: list[Coordinate] = []
    maneuvers = []
    speed_limits = []
    shape_offset = 0
    for index, leg in enumerate(route.legs):
        leg_points = decode_polyline6(leg.encoded_polyline, max_points=max_points)
        coordinates.extend(leg_points if index == 0 else leg_points[1:])
        maneuvers.extend(
            replace(
                maneuver,
                begin_shape_index=maneuver.begin_shape_index + shape_offset,
                end_shape_index=maneuver.end_shape_index + shape_offset,
            )
            for maneuver in leg.maneuvers
        )
        speed_limits.extend(
            replace(
                limit,
                begin_shape_index=limit.begin_shape_index + shape_offset,
                end_shape_index=limit.end_shape_index + shape_offset,
            )
            for limit in leg.speed_limits
        )
        shape_offset += len(leg_points) - 1
    if len(coordinates) > max_points:
        raise RoutingProviderError("Route geometry exceeds the configured point limit")
    return BaseRoute(
        distance_meters=route.distance_meters,
        duration_seconds=route.duration_seconds,
        encoded_polyline=encode_polyline6(tuple(coordinates)),
        maneuvers=tuple(maneuvers),
        provider=route.provider,
        traffic_aware=route.traffic_aware,
        traffic_delay_seconds=route.traffic_delay_seconds,
        traffic_fallback_used=route.traffic_fallback_used,
        speed_limits=tuple(speed_limits),
        speed_limit_source=next(
            (leg.speed_limit_source for leg in route.legs if leg.speed_limit_source),
            None,
        ),
    )


def waypoint_route_leg_fraction_boundaries(
    route: WaypointRoute,
    *,
    max_points: int,
) -> tuple[float, ...]:
    """Return the joined-shape fraction at the end of every leg except the last.

    PostGIS ``ST_LineLocatePoint`` measures progress on the joined WGS84 line.
    Using the same planar segment lengths here lets predictive routing map each
    corridor candidate back to the mandatory waypoint leg it belongs to.
    """
    if len(route.legs) <= 1:
        return ()
    leg_lengths: list[float] = []
    for leg in route.legs:
        points = decode_polyline6(leg.encoded_polyline, max_points=max_points)
        leg_lengths.append(
            sum(
                (
                    (end.latitude - start.latitude) ** 2
                    + (end.longitude - start.longitude) ** 2
                )
                ** 0.5
                for start, end in zip(points, points[1:], strict=False)
            )
        )
    total = sum(leg_lengths)
    if total <= 0:
        raise RoutingProviderError("Waypoint route geometry has no measurable length")
    cumulative = 0.0
    boundaries: list[float] = []
    for length in leg_lengths[:-1]:
        cumulative += length
        boundaries.append(cumulative / total)
    return tuple(boundaries)


def _decode_value(encoded: str, index: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        if index >= len(encoded):
            raise RoutingProviderError("Route geometry is truncated")
        value = ord(encoded[index]) - 63
        index += 1
        if not 0 <= value <= 63:
            raise RoutingProviderError("Route geometry contains an invalid character")
        result |= (value & 0x1F) << shift
        if value < 0x20:
            break
        shift += 5
        if shift > 60:
            raise RoutingProviderError("Route geometry contains an invalid value")
    decoded = ~(result >> 1) if result & 1 else result >> 1
    return decoded, index


def _encode_value(value: int) -> str:
    shifted = ~(value << 1) if value < 0 else value << 1
    characters: list[str] = []
    while shifted >= 0x20:
        characters.append(chr((0x20 | (shifted & 0x1F)) + 63))
        shifted >>= 5
    characters.append(chr(shifted + 63))
    return "".join(characters)
