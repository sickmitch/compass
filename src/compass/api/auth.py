import secrets
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel

from compass.api.contracts import ErrorResponse
from compass.config import Settings, get_api_settings


class ApiAuthenticationError(Exception):
    """Raised when a protected Compass endpoint receives invalid credentials."""


class AuthenticatedApiUser(BaseModel):
    auth_enabled: bool
    username: str | None


class AuthenticationCheckResponse(BaseModel):
    authenticated: bool = True
    auth_enabled: bool


class OptionalHTTPBasic(HTTPBasic):
    async def __call__(self, request: Request) -> HTTPBasicCredentials | None:
        try:
            return await super().__call__(request)
        except HTTPException:
            return None


basic_auth = OptionalHTTPBasic(auto_error=False, realm="Compass API")


async def require_api_user(
    credentials: Annotated[HTTPBasicCredentials | None, Depends(basic_auth)],
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> AuthenticatedApiUser:
    if not settings.api_auth_enabled:
        return AuthenticatedApiUser(auth_enabled=False, username=None)

    expected_username = settings.api_auth_username.encode("utf-8")
    expected_password = settings.api_auth_password.get_secret_value().encode("utf-8")
    supplied_username = credentials.username.encode("utf-8") if credentials else b""
    supplied_password = credentials.password.encode("utf-8") if credentials else b""
    if not (
        secrets.compare_digest(supplied_username, expected_username)
        and secrets.compare_digest(supplied_password, expected_password)
    ):
        raise ApiAuthenticationError

    return AuthenticatedApiUser(auth_enabled=True, username=settings.api_auth_username)


router = APIRouter(prefix="/api/v1/auth", tags=["authentication"])


@router.get(
    "/check",
    response_model=AuthenticationCheckResponse,
    responses={401: {"model": ErrorResponse, "description": "Invalid API credentials."}},
)
async def check_authentication(
    user: Annotated[AuthenticatedApiUser, Depends(require_api_user)],
) -> AuthenticationCheckResponse:
    return AuthenticationCheckResponse(
        auth_enabled=user.auth_enabled,
    )
