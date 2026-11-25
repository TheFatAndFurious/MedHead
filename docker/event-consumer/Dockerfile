FROM python:3.13.5-slim

WORKDIR /app
COPY docker/event-consumer/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY docker/event-consumer/consumer.py .

USER nobody
ENTRYPOINT ["python", "-u", "consumer.py"]
