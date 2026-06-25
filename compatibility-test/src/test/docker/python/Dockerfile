FROM python:3.12.8-slim-bookworm

WORKDIR /compat
COPY requirements.txt /compat/requirements.txt
RUN pip install --no-cache-dir -r /compat/requirements.txt
COPY compat.py /compat/compat.py
ENTRYPOINT ["python", "/compat/compat.py"]
