import asyncio
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field, fields
from math import ceil
from uuid import UUID, uuid4

from compass.search.destination_domain import (
    DestinationRateLimitedError,
    DestinationSearchProvider,
    DestinationSuggestion,
    DestinationSuggestRequest,
    NavigationTarget,
    ResolvedDestinationSelection,
    StaleDestinationSelectionError,
)


@dataclass(slots=True)
class _SearchSession:
    owner: str
    provider_token: str
    language: str
    revision: int = -1
    query: str | None = None
    suggestions: tuple[DestinationSuggestion, ...] = ()
    updated_at: float = field(default_factory=time.monotonic)
    concluded: bool = False
    suggestion_task: asyncio.Task[tuple[DestinationSuggestion, ...]] | None = None
    resolutions: dict[str, asyncio.Task[ResolvedDestinationSelection]] = field(default_factory=dict)


@dataclass(slots=True)
class DestinationSearchMetrics:
    sessions_started: int = 0
    sessions_concluded: int = 0
    sessions_abandoned: int = 0
    suggest_requests: int = 0
    resolve_requests: int = 0
    suggest_latency_ms_total: int = 0
    resolve_latency_ms_total: int = 0
    resolutions_succeeded: int = 0
    provider_errors: int = 0
    rate_limited: int = 0
    tomtom_destination_calls: int = 0

    def snapshot(self) -> dict[str, int]:
        return {item.name: int(getattr(self, item.name)) for item in fields(self)}


class PerUserRequestLimiter:
    def __init__(self, requests_per_minute: int) -> None:
        if requests_per_minute < 1:
            raise ValueError("destination request rate must be positive")
        self._limit = requests_per_minute
        self._requests: dict[str, deque[float]] = defaultdict(deque)
        self._lock = asyncio.Lock()

    async def acquire(self, owner: str) -> bool:
        now = time.monotonic()
        async with self._lock:
            requests = self._requests[owner]
            while requests and now - requests[0] >= 60:
                requests.popleft()
            if len(requests) >= self._limit:
                return False
            requests.append(now)
            return True


class DestinationSearchService:
    def __init__(
        self,
        *,
        providers: tuple[DestinationSearchProvider, ...],
        session_ttl_seconds: int = 900,
        max_concurrency: int = 8,
        requests_per_user_minute: int = 60,
        minimum_query_characters: int = 3,
    ) -> None:
        if len(providers) != 1 or providers[0].provider_name != "google_places_new":
            raise ValueError("testing registry must contain only google_places_new")
        self._providers = {provider.provider_name: provider for provider in providers}
        self._session_ttl_seconds = session_ttl_seconds
        self._sessions: dict[tuple[str, str], _SearchSession] = {}
        self._lock = asyncio.Lock()
        self._provider_slots = asyncio.Semaphore(max_concurrency)
        self._limiter = PerUserRequestLimiter(requests_per_user_minute)
        self._minimum_query_characters = minimum_query_characters
        self._provider_backoff_until: dict[str, float] = {}
        self.metrics = DestinationSearchMetrics()

    async def suggest(
        self,
        owner: str,
        request: DestinationSuggestRequest,
    ) -> tuple[DestinationSuggestion, ...]:
        _require_uuid4(request.session_id)
        if len("".join(request.query.split())) < self._minimum_query_characters:
            raise ValueError("destination query is shorter than configured minimum")
        await self._collect_expired()
        self._require_provider_backoff_elapsed(owner)
        if not await self._limiter.acquire(owner):
            self.metrics.rate_limited += 1
            raise DestinationRateLimitedError(60)
        key = (owner, request.session_id)
        async with self._lock:
            session = self._sessions.get(key)
            if session is None:
                session = _SearchSession(
                    owner=owner,
                    provider_token=str(uuid4()),
                    language=request.language,
                )
                self._sessions[key] = session
                self.metrics.sessions_started += 1
            if session.concluded:
                raise StaleDestinationSelectionError("search session is already concluded")
            if request.revision < session.revision:
                raise StaleDestinationSelectionError("search revision is stale")
            normalized_query = request.query.strip()
            if request.revision == session.revision and normalized_query == session.query:
                if session.suggestion_task is None:
                    return session.suggestions
                task = session.suggestion_task
                created_task = False
            else:
                if request.revision == session.revision:
                    raise StaleDestinationSelectionError("revision was reused for another query")
                session.revision = request.revision
                session.query = normalized_query
                session.language = request.language
                session.updated_at = time.monotonic()
                task = asyncio.create_task(
                    self._suggest_provider(
                        self._providers["google_places_new"],
                        request,
                        session.provider_token,
                    )
                )
                session.suggestion_task = task
                self.metrics.suggest_requests += 1
                created_task = True
        try:
            suggestions = await asyncio.shield(task)
        except DestinationRateLimitedError as error:
            if created_task:
                self.metrics.rate_limited += 1
                self._record_provider_backoff(owner, error)
                async with self._lock:
                    if session.suggestion_task is task:
                        session.suggestion_task = None
            raise
        except Exception:
            if created_task:
                self.metrics.provider_errors += 1
                async with self._lock:
                    if session.suggestion_task is task:
                        session.suggestion_task = None
            raise
        async with self._lock:
            current = self._sessions.get(key)
            if current is not session or current.revision != request.revision:
                raise StaleDestinationSelectionError("suggestion response is stale")
            current.suggestions = suggestions
            if current.suggestion_task is task:
                current.suggestion_task = None
            current.updated_at = time.monotonic()
        return suggestions

    async def _suggest_provider(
        self,
        provider: DestinationSearchProvider,
        request: DestinationSuggestRequest,
        provider_token: str,
    ) -> tuple[DestinationSuggestion, ...]:
        started = time.monotonic()
        try:
            async with self._provider_slots:
                return await provider.suggest(request, provider_token)
        finally:
            self.metrics.suggest_latency_ms_total += round((time.monotonic() - started) * 1_000)

    async def resolve(
        self,
        *,
        owner: str,
        session_id: str,
        revision: int,
        provider: str,
        provider_ref: str,
    ) -> tuple[ResolvedDestinationSelection, NavigationTarget]:
        _require_uuid4(session_id)
        self._require_provider_backoff_elapsed(owner)
        if not await self._limiter.acquire(owner):
            self.metrics.rate_limited += 1
            raise DestinationRateLimitedError(60)
        key = (owner, session_id)
        async with self._lock:
            session = self._sessions.get(key)
            if session is None or revision != session.revision:
                raise StaleDestinationSelectionError("selection does not belong to this revision")
            if provider != "google_places_new" or provider not in self._providers:
                raise StaleDestinationSelectionError("selection provider is not active")
            if provider_ref not in {item.provider_ref for item in session.suggestions}:
                raise StaleDestinationSelectionError("selection was not suggested in this session")
            task = session.resolutions.get(provider_ref)
            if task is None:
                task = asyncio.create_task(
                    self._resolve_provider(
                        self._providers[provider],
                        provider_ref,
                        session.language,
                        session.provider_token,
                    )
                )
                session.resolutions[provider_ref] = task
                self.metrics.resolve_requests += 1
        try:
            selection = await asyncio.shield(task)
        except DestinationRateLimitedError as error:
            self.metrics.rate_limited += 1
            self._record_provider_backoff(owner, error)
            async with self._lock:
                if session.resolutions.get(provider_ref) is task:
                    session.resolutions.pop(provider_ref, None)
            raise
        except Exception:
            self.metrics.provider_errors += 1
            # A failed Details request is not retained. A deliberate user retry may
            # issue another provider call; exactly-once billing cannot be promised.
            async with self._lock:
                if session.resolutions.get(provider_ref) is task:
                    session.resolutions.pop(provider_ref, None)
            raise
        async with self._lock:
            current = self._sessions.get(key)
            if current is not session or current.revision != revision:
                raise StaleDestinationSelectionError("resolved selection is stale")
            if not current.concluded:
                current.concluded = True
                current.updated_at = time.monotonic()
                self.metrics.sessions_concluded += 1
                self.metrics.resolutions_succeeded += 1
        return selection, NavigationTarget(
            coordinate=selection.coordinate,
            provider_ref=selection.provider_ref,
        )

    async def _resolve_provider(
        self,
        provider: DestinationSearchProvider,
        provider_ref: str,
        language: str,
        provider_token: str,
    ) -> ResolvedDestinationSelection:
        started = time.monotonic()
        try:
            async with self._provider_slots:
                return await provider.resolve(
                    provider_ref,
                    language=language,
                    provider_session_token=provider_token,
                )
        finally:
            self.metrics.resolve_latency_ms_total += round((time.monotonic() - started) * 1_000)

    async def _collect_expired(self) -> None:
        now = time.monotonic()
        async with self._lock:
            expired = [
                key
                for key, session in self._sessions.items()
                if now - session.updated_at >= self._session_ttl_seconds
            ]
            for key in expired:
                session = self._sessions.pop(key)
                if not session.concluded:
                    self.metrics.sessions_abandoned += 1

    def _require_provider_backoff_elapsed(self, owner: str) -> None:
        remaining = self._provider_backoff_until.get(owner, 0) - time.monotonic()
        if remaining > 0:
            self.metrics.rate_limited += 1
            raise DestinationRateLimitedError(max(1, ceil(remaining)))

    def _record_provider_backoff(
        self,
        owner: str,
        error: BaseException | None,
    ) -> None:
        if not isinstance(error, DestinationRateLimitedError):
            return
        delay = error.retry_after_seconds or 60
        self._provider_backoff_until[owner] = max(
            self._provider_backoff_until.get(owner, 0),
            time.monotonic() + delay,
        )


def _require_uuid4(value: str) -> None:
    try:
        parsed = UUID(value)
    except ValueError as error:
        raise ValueError("destination session id must be UUID v4") from error
    if parsed.version != 4 or str(parsed) != value.lower():
        raise ValueError("destination session id must be canonical UUID v4")
