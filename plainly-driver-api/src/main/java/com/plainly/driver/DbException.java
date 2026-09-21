package com.plainly.driver;

/** 驱动层统一异常。把各家 SQLException 的差异挡在契约之外。 */
public class DbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sqlState;

    public DbException(String message) {
        this(message, null, null);
    }

    public DbException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public DbException(String message, String sqlState, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }
}
