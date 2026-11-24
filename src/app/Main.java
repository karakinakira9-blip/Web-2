package app;

import com.fastcgi.FCGIInterface;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class Main {
    private static final List<Result> HISTORY = new ArrayList<Result>();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final double EPSILON = 1e-9;
    private static final int[] ALLOWED_X = {-5, -4, -3, -2, -1, 0, 1, 2, 3};

    private Main() {
    }

    public static void main(String[] args) {
        FCGIInterface fcgi = new FCGIInterface();
        System.err.println("FastCGI server started on port " + System.getProperty("FCGI_PORT"));

        while (fcgi.FCGIaccept() >= 0) {
            handleRequest();
        }
    }

    private static void handleRequest() {
        long startedAt = System.nanoTime();

        try {
            Properties environment = FCGIInterface.request.params;
            String method = environment.getProperty("REQUEST_METHOD", "");
            if (!"POST".equalsIgnoreCase(method.trim())) {
                sendJson(405, "Method Not Allowed", errorJson("Поддерживается только метод POST."));
                return;
            }

            Map<String, String> params = readForm(environment);
            String action = params.getOrDefault("action", "check").trim();

            if ("history".equals(action)) {
                sendJson(200, "OK", historyJson());
                return;
            }
            if ("clear".equals(action)) {
                synchronized (HISTORY) {
                    HISTORY.clear();
                }
                sendJson(200, "OK", historyJson());
                return;
            }
            if (!"check".equals(action)) {
                throw new IllegalArgumentException("Неизвестное действие.");
            }

            double x = requiredNumber(params, "x", "X");
            double y = requiredNumber(params, "y", "Y");
            double r = requiredNumber(params, "r", "R");
            validateValues(x, y, r);

            boolean hit = AreaChecker.isHit(x, y, r);
            long executionTimeNs = System.nanoTime() - startedAt;
            Result result = new Result(
                    x,
                    y,
                    r,
                    hit,
                    OffsetDateTime.now().format(TIME_FORMAT),
                    executionTimeNs
            );

            synchronized (HISTORY) {
                HISTORY.add(result);
            }
            sendJson(200, "OK", successJson(result));
        } catch (RequestTooLargeException exception) {
            sendJson(413, "Payload Too Large", errorJson(exception.getMessage()));
        } catch (UnsupportedMediaTypeException exception) {
            sendJson(415, "Unsupported Media Type", errorJson(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            sendJson(400, "Bad Request", errorJson(exception.getMessage()));
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            sendJson(500, "Internal Server Error", errorJson("Внутренняя ошибка сервера."));
        }
    }

    private static Map<String, String> readForm(Properties environment) throws IOException {
        String contentType = environment.getProperty("CONTENT_TYPE", "");
        if (!contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            throw new UnsupportedMediaTypeException(
                    "Ожидается Content-Type application/x-www-form-urlencoded."
            );
        }

        int contentLength;
        try {
            contentLength = Integer.parseInt(environment.getProperty("CONTENT_LENGTH", "0"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Некорректная длина тела запроса.");
        }

        if (contentLength < 0) {
            throw new IllegalArgumentException("Некорректная длина тела запроса.");
        }
        if (contentLength > MAX_BODY_BYTES) {
            throw new RequestTooLargeException("Тело запроса превышает 8 КБ.");
        }

        byte[] bodyBytes = FCGIInterface.request.inStream.readNBytes(contentLength);
        if (bodyBytes.length != contentLength) {
            throw new IllegalArgumentException("Тело запроса получено не полностью.");
        }
        return parseUrlEncoded(new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static Map<String, String> parseUrlEncoded(String body) {
        Map<String, String> params = new HashMap<String, String>();
        if (body == null || body.isEmpty()) {
            return params;
        }

        String[] pairs = body.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String rawKey = separator < 0 ? pair : pair.substring(0, separator);
            String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            try {
                String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name());
                String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name());
                params.put(key, value);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Некорректно закодировано тело запроса.");
            }
        }
        return params;
    }

    private static double requiredNumber(
            Map<String, String> params,
            String key,
            String displayName
    ) {
        String raw = params.get(key);
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Параметр " + displayName + " обязателен.");
        }

        try {
            double value = Double.parseDouble(raw.trim().replace(',', '.'));
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Параметр " + displayName + " должен быть числом.");
        }
    }

    private static void validateValues(double x, double y, double r) {
        boolean allowedX = false;
        for (int value : ALLOWED_X) {
            if (Math.abs(x - value) <= EPSILON) {
                allowedX = true;
                break;
            }
        }
        if (!allowedX) {
            throw new IllegalArgumentException(
                    "X должен быть одним из значений -5, -4, -3, -2, -1, 0, 1, 2, 3."
            );
        }
        if (y < -3.0 || y > 3.0) {
            throw new IllegalArgumentException("Y должен находиться в диапазоне [-3; 3].");
        }
        if (r < 1.0 || r > 4.0) {
            throw new IllegalArgumentException("R должен находиться в диапазоне [1; 4].");
        }
    }

    private static String successJson(Result current) {
        StringBuilder json = new StringBuilder();
        json.append("{\"status\":\"ok\",\"point\":");
        json.append(current.toJson());
        json.append(",\"history\":[");
        appendHistory(json);
        json.append("]}");
        return json.toString();
    }

    private static String historyJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\"status\":\"ok\",\"serverTime\":");
        json.append(jsonString(OffsetDateTime.now().format(TIME_FORMAT)));
        json.append(",\"history\":[");
        appendHistory(json);
        json.append("]}");
        return json.toString();
    }

    private static void appendHistory(StringBuilder json) {
        synchronized (HISTORY) {
            for (int index = 0; index < HISTORY.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append(HISTORY.get(index).toJson());
            }
        }
    }

    private static String errorJson(String message) {
        return "{\"status\":\"error\",\"message\":" + jsonString(message) + "}";
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            switch (symbol) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (symbol < 32) {
                        escaped.append(String.format("\\u%04x", (int) symbol));
                    } else {
                        escaped.append(symbol);
                    }
            }
        }
        return escaped.append('"').toString();
    }

    private static void sendJson(int status, String reason, String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "\r\n";

        try {
            OutputStream output = FCGIInterface.request.outStream;
            output.write(headers.getBytes(StandardCharsets.UTF_8));
            output.write(bodyBytes);
            output.flush();
        } catch (IOException exception) {
            throw new RuntimeException("Не удалось отправить ответ.", exception);
        }
    }

    private static final class Result {
        private final double x;
        private final double y;
        private final double r;
        private final boolean hit;
        private final String serverTime;
        private final long executionTimeNs;

        private Result(double x, double y, double r, boolean hit,
                       String serverTime, long executionTimeNs) {
            this.x = x;
            this.y = y;
            this.r = r;
            this.hit = hit;
            this.serverTime = serverTime;
            this.executionTimeNs = executionTimeNs;
        }

        private String toJson() {
            return "{\"x\":" + x
                    + ",\"y\":" + y
                    + ",\"r\":" + r
                    + ",\"hit\":" + hit
                    + ",\"serverTime\":" + jsonString(serverTime)
                    + ",\"executionTimeNs\":" + executionTimeNs
                    + "}";
        }
    }

    private static final class RequestTooLargeException extends IllegalArgumentException {
        private RequestTooLargeException(String message) {
            super(message);
        }
    }

    private static final class UnsupportedMediaTypeException extends IllegalArgumentException {
        private UnsupportedMediaTypeException(String message) {
            super(message);
        }
    }
}
