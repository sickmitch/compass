from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ErrorResponse(StrictModel):
    code: str
    message: str


def error_response(status_code: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content=ErrorResponse(code=code, message=message).model_dump(),
    )
