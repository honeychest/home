package com.chs.springboot;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class CalculatorController {

    @GetMapping("/calculate")
    public double calculate(
            @RequestParam double a,
            @RequestParam double b,
            @RequestParam String op) {

        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b != 0 ? a / b : 0; // 0으로 나누기 방지
            default -> 0;
        };
    }
}