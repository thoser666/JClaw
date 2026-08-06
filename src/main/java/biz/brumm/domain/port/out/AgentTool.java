package biz.brumm.domain.port.out;

/**
 * Port für Werkzeuge, die der Agent während seiner Ausführung aufrufen kann.
 * <p>
 * Implementierungen sind Spring-Beans, die ihre ausführbare Logik über mit
 * {@code @Tool} annotierte Methoden (Spring AI) kennzeichnen. Die Namen und
 * Beschreibungen der Werkzeuge werden über die Annotation definiert und dem
 * Sprachmodell automatisch als Tool-Schema mitgegeben.
 */
public interface AgentTool {
}
