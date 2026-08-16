package biz.brumm.domain.port.out;

/**
 * Port für die Tool-Policy: entscheidet, ob ein Werkzeug für den Agenten freigeschaltet ist.
 * Deaktivierte Werkzeuge werden dem Sprachmodell nicht als Tool-Schema mitgegeben.
 */
public interface ToolPolicy {

    boolean isToolEnabled(String toolName);
}
