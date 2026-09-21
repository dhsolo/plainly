package com.plainly.driver.query;

/**
 * 插入时撞上已有主键怎么办。
 *
 * <p>放在驱动契约里而不是导入模块里：各家的写法差得很远（{@code INSERT IGNORE}、
 * {@code ON CONFLICT}、{@code MERGE}），只有方言知道该怎么拼。
 */
public enum ConflictPolicy {

    /** 跳过冲突的行，其余照常写入。 */
    SKIP("跳过该行"),

    /** 用新值覆盖已有行。 */
    UPDATE("更新已有行"),

    /** 一撞就停，交由调用方决定是否回滚。 */
    ABORT("中止导入");

    private final String label;

    ConflictPolicy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
