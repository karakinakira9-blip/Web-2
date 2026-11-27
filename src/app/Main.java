package app;

import com.fastcgi.FCGIInterface;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class Main {
    private static final List<Result> HISTORY = new ArrayList<Result>();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final BigDecimal Y_MIN = BigDecimal.valueOf(-3);
    private static final BigDecimal Y_MAX = BigDecimal.valueOf(3);
    private static final BigDecimal R_MIN = BigDecimal.ONE;
    private static final BigDecimal R_MAX = BigDecimal.valueOf(4);

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

            Map<String, List<String>> params = readForm(environment);
            String action = lastValue(params, "action", "check").trim();

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

            Map<Integer, String> selectedX = readSelectedX(params.get("x"));
            String rawY = lastValue(params, "y", "");
            String rawR = lastValue(params, "r", "");
            BigDecimal y = requiredNumber(rawY, "Y");
            BigDecimal r = requiredNumber(rawR, "R");
            validateRange(y, Y_MIN, Y_MAX, "Y");
            validateRange(r, R_MIN, R_MAX, "R");

            List<Result> results = new ArrayList<>();
            for (Map.Entry<Integer, String> x : selectedX.entrySet()) {
                boolean hit = AreaChecker.isHit(x.getKey(), y, r);
                results.add(new Result(x.getValue(), rawY, rawR, hit,
                        OffsetDateTime.now().format(TIME_FORMAT), System.nanoTime() - startedAt));
            }
            synchronized (HISTORY) {
                HISTORY.addAll(results);
            }
            sendJson(200, "OK", successJson(results));
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

    private static Map<String, List<String>> readForm(Properties environment) throws IOException {
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

    private static Map<String, List<String>> parseUrlEncoded(String body) {
        Map<String, List<String>> params = new HashMap<String, List<String>>();
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
                params.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Некорректно закодировано тело запроса.");
            }
        }
        return params;
    }

    private static String lastValue(Map<String, List<String>> params, String key, String fallback) {
        List<String> values = params.get(key);
        return values == null ? fallback : values.get(values.size() - 1);
    }

    private static BigDecimal requiredNumber(String raw, String name) {
        if (raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Параметр " + name + " обязателен.");
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim().replace(',', '.'));
            // Ограничиваем масштаб и для старых клиентов, отправляющих числа с экспонентой.
            if (Math.abs((long) value.scale()) > MAX_BODY_BYTES) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Параметр " + name + " должен быть числом.");
        }
    }

    private static Map<Integer, String> readSelectedX(List<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("Выберите хотя бы одно значение X.");
        }

        Map<Integer, String> selectedX = new LinkedHashMap<>();
        for (String raw : values) {
            BigDecimal value = requiredNumber(raw, "X");
            try {
                validateRange(value, BigDecimal.valueOf(-5), BigDecimal.valueOf(3), "X");
                selectedX.putIfAbsent(value.intValueExact(), raw);
            } catch (ArithmeticException | IllegalArgumentException exception) {
                throw new IllegalArgumentException("X должен быть целым числом от -5 до 3.");
            }
        }
        return selectedX;
    }

    private static void validateRange(BigDecimal value, BigDecimal min, BigDecimal max, String name) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(
                    "Параметр " + name + " должен быть в диапазоне [" + min + "; " + max + "].");
        }
    }

    private static String successJson(List<Result> results) {
        StringBuilder json = new StringBuilder();
        json.append("{\"status\":\"ok\",\"point\":").append(results.get(0).toJson());
        json.append(",\"points\":[");
        appendResults(json, results);
        json.append("],\"history\":[");
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
            appendResults(json, HISTORY);
        }
    }

    private static void appendResults(StringBuilder json, List<Result> results) {
        for (int index = 0; index < results.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(results.get(index).toJson());
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
        private final String x;
        private final String y;
        private final String r;
        private final boolean hit;
        private final String serverTime;
        private final long executionTimeNs;

        private Result(String x, String y, String r, boolean hit,
                       String serverTime, long executionTimeNs) {
            this.x = x;
            this.y = y;
            this.r = r;
            this.hit = hit;
            this.serverTime = serverTime;
            this.executionTimeNs = executionTimeNs;
        }

        private String toJson() {
            return "{\"x\":" + jsonString(x)
                    + ",\"y\":" + jsonString(y)
                    + ",\"r\":" + jsonString(r)
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
