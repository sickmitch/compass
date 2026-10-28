FROM docker:27.5.1-cli
RUN apk add --no-cache python3 tzdata
COPY scripts/scheduled-update.py /opt/compass/scheduled-update.py
ENV PYTHONUNBUFFERED=1 \
    COMPASS_REPOSITORY=/workspace \
    COMPASS_UPDATE_STATE=/state
ENTRYPOINT ["python3", "/opt/compass/scheduled-update.py"]
CMD ["serve"]
