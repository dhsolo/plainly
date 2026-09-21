package com.plainly.driver;

/**
 * 一批 DDL 执行失败。
 *
 * <p>之所以要专门一个异常类型：<b>失败后库处于什么状态，各家不一样</b>。
 * PostgreSQL / SQLite 支持事务性 DDL，整批回滚，库还是原样；
 * MySQL / Oracle / H2 的 DDL 会隐式提交，前面成功的那些<b>已经生效且撤不回来</b>。
 *
 * <p>用户必须知道自己现在处在哪种状态——是「什么都没发生，改完再来一次」，
 * 还是「已经改了三条，第四条炸了，得先看看现在的库长什么样」。
 * 把这个区别藏起来只会让人在生产库上做出错误判断。
 */
public class DdlBatchException extends DbException {

    private static final long serialVersionUID = 1L;

    private final int executedCount;
    private final int totalCount;
    private final String failedStatement;
    private final boolean rolledBack;

    public DdlBatchException(int executedCount, int totalCount, String failedStatement,
                             boolean rolledBack, Throwable cause) {
        super(buildMessage(executedCount, totalCount, failedStatement, rolledBack, cause), cause);
        this.executedCount = executedCount;
        this.totalCount = totalCount;
        this.failedStatement = failedStatement;
        this.rolledBack = rolledBack;
    }

    private static String buildMessage(int executed, int total, String statement,
                                       boolean rolledBack, Throwable cause) {
        StringBuilder sb = new StringBuilder();
        sb.append("第 ").append(executed + 1).append(" / ").append(total).append(" 条语句失败：\n");
        sb.append(statement).append("\n\n");
        if (cause != null && cause.getMessage() != null) {
            sb.append(cause.getMessage()).append("\n\n");
        }
        if (rolledBack) {
            sb.append("整批已回滚，库结构未发生任何改变。");
        } else {
            sb.append("⚠ 该数据库的 DDL 会隐式提交，无法回滚。");
            if (executed > 0) {
                sb.append("前 ").append(executed).append(" 条变更已经生效且不可撤销，")
                        .append("请先确认当前库结构，再决定如何继续。");
            } else {
                sb.append("本次没有任何语句执行成功，库结构未改变。");
            }
        }
        return sb.toString();
    }

    /** 已经成功执行的语句条数。 */
    public int executedCount() {
        return executedCount;
    }

    public int totalCount() {
        return totalCount;
    }

    public String failedStatement() {
        return failedStatement;
    }

    /** 是否已整批回滚。为 {@code false} 时前 {@link #executedCount()} 条变更已生效。 */
    public boolean rolledBack() {
        return rolledBack;
    }

    /** 库是否处于「改了一半」的状态。 */
    public boolean leftPartialState() {
        return !rolledBack && executedCount > 0;
    }
}
