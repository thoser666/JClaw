package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.port.out.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CalculatorTool implements AgentTool {

    @Tool(name = "calculate",
            description = "Berechnet einen einfachen arithmetischen Ausdruck mit +, -, *, / und Klammern.")
    public String calculate(
            @ToolParam(description = "Arithmetischer Ausdruck, z. B. '(12 + 4) * 2 / 8'.") String expression) {
        if (expression == null || expression.isBlank()) {
            return "Fehler: Der Ausdruck darf nicht leer sein.";
        }
        try {
            return ArithmeticExpressionEvaluator.format(ArithmeticExpressionEvaluator.evaluate(expression));
        } catch (IllegalArgumentException e) {
            return "Fehler: " + e.getMessage();
        }
    }
}
