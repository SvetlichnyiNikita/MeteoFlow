package com.example.meteo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONParserConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

public class Main {
    private double latitude;
    private double longitude;
    private int limit;

    public static void main(String[] args) {
        Main application = new Main();
        String apiKey = System.getenv("YANDEX_WEATHER_API_KEY");
        int status = application.run(args, apiKey);
        if (status != 0) {
            System.exit(status);
        }
    }

    int run(String[] args, String apiKey) {
        try {
            readArguments(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Запуск: java -jar target/meteoflow.jar [lat lon limit]");
            return 1;
        }
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Задайте API-ключ в переменной окружения YANDEX_WEATHER_API_KEY.");
            return 1;
        }

        HttpRequest request;
        try {
            request = createRequest(apiKey.strip());
        } catch (IllegalArgumentException e) {
            System.err.println("Не удалось создать запрос: проверьте формат API-ключа.");
            return 1;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return printResponse(response.statusCode(), response.body());
        } catch (HttpTimeoutException e) {
            System.err.println("Истекло время ожидания ответа Яндекс Погоды.");
        } catch (IOException e) {
            System.err.println("Не удалось получить погоду: проверьте подключение к сети и доступность API.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Запрос погоды прерван.");
        }
        return 1;
    }

    void readArguments(String[] args) {
        latitude = 50.5956;
        longitude = 36.5873;
        limit = 3;
        if (args.length != 0 && args.length != 3) {
            throw new IllegalArgumentException("Укажите три аргумента: широту, долготу и количество суток.");
        }
        if (args.length == 3) {
            try {
                latitude = Double.parseDouble(args[0].replace(',', '.'));
                longitude = Double.parseDouble(args[1].replace(',', '.'));
                limit = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Координаты должны быть числами, а limit — целым числом.");
            }
        }
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Широта lat должна быть от -90 до 90.");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Долгота lon должна быть от -180 до 180.");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Количество суток limit должно быть положительным.");
        }
    }

    HttpRequest createRequest(String apiKey) {
        String address = "https://api.weather.yandex.ru/v2/forecast?lat=" + latitude
                + "&lon=" + longitude + "&limit=" + limit;
        return HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofSeconds(20))
                .header("X-Yandex-Weather-Key", apiKey)
                .GET()
                .build();
    }

    int printResponse(int status, String body) {
        System.out.print(body);
        System.out.println();
        System.out.println();
        if (status < 200 || status >= 300) {
            System.err.printf("Яндекс Погода вернула HTTP %d. Проверьте ключ, доступ к REST v2 и параметры запроса.%n",
                    status);
            return 1;
        }

        JSONObject weather;
        try {
            JSONParserConfiguration settings = new JSONParserConfiguration().withStrictMode(true);
            weather = new JSONObject(body, settings);
        } catch (JSONException e) {
            System.err.println("Яндекс Погода вернула некорректный JSON: ожидался объект с погодными данными.");
            return 1;
        }

        int result = 0;
        try {
            double temperature = readTemperature(weather);
            System.out.printf(Locale.ROOT, "Текущая температура: %.2f °C%n", temperature);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            result = 1;
        }

        try {
            double average = calculateAverage(weather, limit);
            System.out.printf(Locale.ROOT, "Средняя дневная температура за %d суток: %.2f °C%n", limit, average);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            result = 1;
        }
        return result;
    }

    double readTemperature(JSONObject weather) {
        JSONObject fact = weather.optJSONObject("fact");
        if (!isNumber(fact, "temp")) {
            throw new IllegalArgumentException("Текущая температура недоступна: нет числового значения fact.temp.");
        }
        return fact.getDouble("temp");
    }

    double calculateAverage(JSONObject weather, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Количество суток должно быть положительным.");
        }
        String missingData = "Недостаточно данных для среднего за " + limit + " суток.";
        JSONArray forecasts = weather.optJSONArray("forecasts");
        if (forecasts == null || forecasts.length() < limit) {
            throw new IllegalArgumentException(missingData);
        }

        double sum = 0;
        for (int i = 0; i < limit; i++) {
            JSONObject forecast = forecasts.optJSONObject(i);
            if (forecast == null) {
                throw new IllegalArgumentException(missingData);
            }
            JSONObject parts = forecast.optJSONObject("parts");
            if (parts == null) {
                throw new IllegalArgumentException(missingData);
            }
            JSONObject day = parts.optJSONObject("day");
            if (!isNumber(day, "temp_avg")) {
                throw new IllegalArgumentException(missingData);
            }
            sum += day.getDouble("temp_avg");
        }

        double average = sum / limit;
        if (!Double.isFinite(average)) {
            throw new IllegalArgumentException(missingData);
        }
        return average;
    }

    private boolean isNumber(JSONObject object, String field) {
        if (object == null || !(object.opt(field) instanceof Number)) {
            return false;
        }
        return Double.isFinite(object.getDouble(field));
    }
}
