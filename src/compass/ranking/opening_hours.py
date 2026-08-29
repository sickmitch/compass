import re
from datetime import datetime
from zoneinfo import ZoneInfo

from opening_hours import (
    InvalidCoordinatesError,
    OpeningHours,
    ParserError,
    State,
    UnknownCountryError,
)

from compass.ranking.domain import OpeningHoursEvaluation

PARSER_FAILURES = (
    ParserError,
    InvalidCoordinatesError,
    UnknownCountryError,
    SyntaxError,
    TypeError,
    ValueError,
    OverflowError,
    RuntimeError,
)

_DAY_SELECTOR = r"(?:Mo|Tu|We|Th|Fr|Sa|Su|PH|SH)"
_SPACED_DAY_SELECTOR_COMMA = re.compile(
    rf"\b(?P<left>{_DAY_SELECTOR})\s*,\s+(?={_DAY_SELECTOR}\b)"
)
_QUOTED_COMMENT = re.compile(r'("(?:[^"\\]|\\.)*")')


def _canonicalize_day_selector_commas(expression: str) -> str:
    """Work around opening-hours-py 2.1.4 treating `Su, PH` as two rules.

    OSM weekday and holiday selectors form comma-separated sets. The pinned parser evaluates a
    whitespace-free set correctly but can treat whitespace after the comma as an additional-rule
    separator. Only unquoted day-selector lists are canonicalized; comments and the value returned
    through the API remain unchanged.
    """
    chunks = _QUOTED_COMMENT.split(expression)
    return "".join(
        chunk
        if index % 2
        else _SPACED_DAY_SELECTOR_COMMA.sub(r"\g<left>,", chunk)
        for index, chunk in enumerate(chunks)
    )


def evaluate_opening_hours(
    expression: str | None,
    *,
    eta: datetime,
    latitude: float,
    longitude: float,
    timezone_name: str,
    country: str,
    source_confidence: float | None,
) -> OpeningHoursEvaluation:
    if eta.tzinfo is None or eta.utcoffset() is None:
        raise ValueError("eta must include a UTC offset")

    timezone = ZoneInfo(timezone_name)
    local_eta = eta.astimezone(timezone)
    if expression is None or not expression.strip():
        return OpeningHoursEvaluation(
            state="unknown",
            validation="missing",
            opening_hours=None,
            source=None,
            source_confidence=None,
            evaluated_at=local_eta,
            timezone=timezone_name,
        )

    normalized_expression = expression.strip()
    parser_expression = _canonicalize_day_selector_commas(normalized_expression)
    try:
        opening_hours = OpeningHours(
            parser_expression,
            timezone=timezone,
            country=country,
            coords=(latitude, longitude),
            max_interval_days=366,
        )
        raw_state, raw_comment = opening_hours.state(local_eta)
        state = {
            State.OPEN: "open",
            State.CLOSED: "closed",
            State.UNKNOWN: "unknown",
        }.get(raw_state, "unknown")
        try:
            next_change_at = opening_hours.next_change(local_eta)
        except PARSER_FAILURES:
            next_change_at = None
        return OpeningHoursEvaluation(
            state=state,
            validation="valid",
            opening_hours=normalized_expression,
            source="osm",
            source_confidence=source_confidence,
            evaluated_at=local_eta,
            timezone=timezone_name,
            next_change_at=next_change_at,
            comment=raw_comment or None,
            warnings=tuple(str(warning) for warning in opening_hours.warnings),
        )
    except PARSER_FAILURES:
        return OpeningHoursEvaluation(
            state="unknown",
            validation="invalid",
            opening_hours=normalized_expression,
            source="osm",
            source_confidence=source_confidence,
            evaluated_at=local_eta,
            timezone=timezone_name,
        )
