from compass.routing.domain import Coordinate, RoutingProviderError


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
        f"{coordinate.longitude:.6f} {coordinate.latitude:.6f}"
        for coordinate in coordinates
    )
    return f"LINESTRING({points})"


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
