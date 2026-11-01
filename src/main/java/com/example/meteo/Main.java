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
import java.util.NoSuchElementException;
import java.util.Scanner;

public class Main {
    private double latitude;
    private double longitude;
    private int limit;

    public static void main(String[] args) {
        Main application = new Main();
        Scanner scanner = new Scanner(System.in);
        String apiKey = System.getenv("YANDEX_WEATHER_API_KEY");
        int status = application.run(scanner, apiKey);
        scanner.close();
        if (status != 0) {
            System.exit(status);
        }
    }

    int run(Scanner scanner, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Задайте API-ключ в переменной окружения YANDEX_WEATHER_API_KEY.");
            return 1;
        }

        while (true) {
            try {
                readInput(scanner);
            } catch (NoSuchElementException e) {
                System.err.println("Ввод завершён. Запрос погоды отменён.");
                return 1;
            }

            int status = requestWeather(apiKey.strip());
            if (Thread.currentThread().isInterrupted()) {
                return status;
            }
            if (!askContinue(scanner)) {
                System.out.println("Выход.");
                return status;
            }
        }
    }

    int requestWeather(String apiKey) {
        HttpRequest request;
        try {
            request = createRequest(apiKey);
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

    boolean askContinue(Scanner scanner) {
        while (true) {
            System.out.print("Получить прогноз ещё раз? (д/н): ");
            if (!scanner.hasNextLine()) {
                return false;
            }
            String answer = scanner.nextLine().trim();
            if (answer.equalsIgnoreCase("д") || answer.equalsIgnoreCase("да")
                    || answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes")) {
                return true;
            }
            if (answer.equalsIgnoreCase("н") || answer.equalsIgnoreCase("нет")
                    || answer.equalsIgnoreCase("n") || answer.equalsIgnoreCase("no")) {
                return false;
            }
            System.out.println("Введите д (да) или н (нет).");
        }
    }

    void readInput(Scanner scanner) {
        System.out.println("По умолчанию: Белгород, Россия, прогноз на 3 суток. Enter — оставить значение.");
        latitude = readCoordinate(scanner, "широту", -90, 90, 50.5956);
        longitude = readCoordinate(scanner, "долготу", -180, 180, 36.5873);
        limit = readLimit(scanner);
    }

    private double readCoordinate(Scanner scanner, String name, double min, double max, double defaultValue) {
        while (true) {
            System.out.print("Введите " + name + " [" + defaultValue + "]: ");
            String input = scanner.nextLine().trim().replace(',', '.');
            if (input.isEmpty()) {
                return defaultValue;
            }
            try {
                double coordinate = Double.parseDouble(input);
                if (Double.isFinite(coordinate) && coordinate >= min && coordinate <= max) {
                    return coordinate;
                }
                System.out.println("Допустимый диапазон: от " + min + " до " + max + ". Повторите ввод.");
            } catch (NumberFormatException e) {
                System.out.println("Это не число, повторите ввод.");
            }
        }
    }

    private int readLimit(Scanner scanner) {
        while (true) {
            System.out.print("Введите количество суток [3]: ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                return 3;
            }
            try {
                int days = Integer.parseInt(input);
                if (days > 0) {
                    return days;
                }
            } catch (NumberFormatException e) {
                System.out.println("Нужно целое положительное число, повторите ввод.");
                continue;
            }
            System.out.println("Количество суток должно быть больше нуля, повторите ввод.");
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
