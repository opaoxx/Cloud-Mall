package com.cloudmall.stock.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 库存最终动作结果；仅输出当前分支的原字段，false不作为缺省值省略。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class StockActionResultVO {
  /** 确认完成时为true；回滚分支不输出该字段。 */
  public final Boolean confirmed;

  /** 回滚完成时为true，等待秒杀投影时为false；确认分支不输出。 */
  public final Boolean rolledBack;

  /** 已完成动作是否为幂等重放；待投影分支不输出。 */
  public final Boolean idempotent;

  /** 秒杀释放仍待投影时为true，其余分支不输出。 */
  public final Boolean pending;

  /** 待投影分支固定为UNKNOWN，其余分支不输出。 */
  public final String status;

  private StockActionResultVO(
      Boolean confirmed, Boolean rolledBack, Boolean idempotent, Boolean pending, String status) {
    this.confirmed = confirmed;
    this.rolledBack = rolledBack;
    this.idempotent = idempotent;
    this.pending = pending;
    this.status = status;
  }

  /** 构造确认完成结果，保留首次确认的idempotent=false。 */
  public static StockActionResultVO confirmed(boolean idempotent) {
    return new StockActionResultVO(true, null, idempotent, null, null);
  }

  /** 构造回滚完成结果，保留首次回滚的idempotent=false。 */
  public static StockActionResultVO rolledBack(boolean idempotent) {
    return new StockActionResultVO(null, true, idempotent, null, null);
  }

  /** 构造秒杀释放待投影结果，不把已受理误报为回滚完成。 */
  public static StockActionResultVO pendingRollback() {
    return new StockActionResultVO(null, false, null, true, "UNKNOWN");
  }
}
