import re

from compass.routing.domain import Coordinate
from compass.search.domain import PlaceSearchProvider, PlaceSearchRequest, PlaceSearchResult

_COORDINATE_QUERY = re.compile(
    r"^\s*([+-]?(?:\d+(?:[.,]\d*)?|[.,]\d+))\s*[,; ]\s*"
    r"([+-]?(?:\d+(?:[.,]\d*)?|[.,]\d+))\s*$"
)


async def search_places(
    provider: PlaceSearchProvider,
    request: PlaceSearchRequest,
) -> tuple[PlaceSearchResult, ...]:
    coordinate = parse_coordinate_query(request.query)
    if coordinate is not None:
        label = f"{coordinate.latitude:.6f}, {coordinate.longitude:.6f}"
        return (
            PlaceSearchResult(
                result_id=f"coordinate:{label}",
                display_name=label,
                address=None,
                coordinate=coordinate,
                kind="coordinate",
            ),
        )
    return await provider.search(request)


def parse_coordinate_query(query: str) -> Coordinate | None:
    match = _COORDINATE_QUERY.fullmatch(query)
    if match is None:
        return None
    try:
        latitude = float(match.group(1).replace(",", "."))
        longitude = float(match.group(2).replace(",", "."))
    except ValueError:
        return None
    if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
        return None
    return Coordinate(latitude, longitude)
