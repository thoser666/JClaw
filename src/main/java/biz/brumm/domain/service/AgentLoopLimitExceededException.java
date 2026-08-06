package biz.brumm.domain.service;

public class AgentLoopLimitExceededException extends RuntimeException {

    public AgentLoopLimitExceededException(int maxIterations) {
        super("Der Agent hat die maximale Anzahl von " + maxIterations + " Iteration(en) überschritten.");
    }
}
