# Loki / Grafana Cloud Setup

This application is configured to send logs to Grafana Loki using the `loki-logback-appender`.

## Requirements

You need a Grafana Cloud account (free tier works) to get your Loki credentials.

1.  Sign up or log in to [Grafana Cloud](https://grafana.com/).
2.  In your stack, find the "Loki" section.
3.  Click "Send Logs" or "Details" to find your `URL`, `User` (Username), and create an API Key (Password).

## Environment Variables

You must set the following environment variables for the application to send logs to Loki:

- `LOKI_URL`: The URL of your Loki instance (e.g., `https://logs-prod-us-central1.grafana.net/loki/api/v1/push`)
- `LOKI_USERNAME`: Your Loki User ID (e.g., `123456`)
- `LOKI_PASSWORD`: Your Grafana Cloud API Key / Access Token

## Example (local run)

```bash
export LOKI_URL="https://logs-prod-us-central1.grafana.net/loki/api/v1/push"
export LOKI_USERNAME="123456"
export LOKI_PASSWORD="glc_eyJ..."
./mvnw spring-boot:run
```

## Troubleshooting

If the environment variables are not set, the application might fail to start or log errors about missing properties. Ensure these are set in your deployment environment (e.g., Docker, Kubernetes, etc.).
