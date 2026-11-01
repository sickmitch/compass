import asyncio

import pytest
from fastapi import FastAPI, Request

from compass.api.main import lifespan
from compass.routing.dependencies import get_routing_provider


def test_routing_pool_is_shared_and_closed_even_on_failure():
    async def check():
        app = FastAPI()
        request = Request({"type": "http", "app": app})
        with pytest.raises(RuntimeError, match="shutdown test"):
            async with lifespan(app):
                first, second = await asyncio.gather(
                    get_routing_provider(request), get_routing_provider(request)
                )
                client = app.state.routing_http_client
                assert first._client is client
                assert second._client is client
                assert not client.is_closed
                raise RuntimeError("shutdown test")
        assert client.is_closed
        assert not hasattr(app.state, "routing_http_client")
        async with lifespan(app):
            assert app.state.routing_http_client is not client
            assert not app.state.routing_http_client.is_closed

    asyncio.run(check())
