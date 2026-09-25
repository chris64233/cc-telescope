package com.chris64233.cc.telescope.domain;

/**
 * 提案剩余配额不足以完成预订。
 */
public class QuotaExceededException extends RuntimeException {

    private final String proposalNo;
    private final int requestedMinutes;
    private final int remainingMinutes;

    public QuotaExceededException(String proposalNo, int requestedMinutes, int remainingMinutes) {
        super("提案 " + proposalNo + " 剩余配额 " + remainingMinutes
                + " 分钟，无法预订 " + requestedMinutes + " 分钟");
        this.proposalNo = proposalNo;
        this.requestedMinutes = requestedMinutes;
        this.remainingMinutes = remainingMinutes;
    }

    public String getProposalNo() {
        return proposalNo;
    }

    public int getRequestedMinutes() {
        return requestedMinutes;
    }

    public int getRemainingMinutes() {
        return remainingMinutes;
    }
}
