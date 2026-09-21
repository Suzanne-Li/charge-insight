# 最近一周时间语义
<!-- knowledge-id: scope.time.latest-seven-days -->
“最近一周”“近 7 天”以 `v_daily_group_operation` 中最新 `stat_date` 为结束日，向前取连续 7 个自然日。没有可用最新日期时不能猜测时间范围。

# 显式日期范围
<!-- knowledge-id: scope.time.explicit-date-range -->
显式日期范围支持 `YYYY-MM-DD 至 YYYY-MM-DD` 及等价分隔形式。开始日期不能晚于结束日期，单次受控查询最多 31 天。

# 上一周期对比
<!-- knowledge-id: scope.time.previous-period -->
归因类问题的上一周期长度必须与当前周期一致，结束日为当前开始日前一天。上一周期只用于同期比较，不可把不同长度区间直接对比。

# 区域权限范围
<!-- knowledge-id: scope.region.access-boundary -->
区域由 `region_name` 限定。ANALYST 只能查询 JWT 授权区域，拥有 `*` 范围的管理员才能执行无区域的跨区域排行；模型上下文不能扩大该范围。

# 城市与桩群范围
<!-- knowledge-id: scope.city-group.entity-resolution -->
城市、桩群必须在授权区域内由实体解析器匹配。唯一模糊匹配可以继续，多个候选必须返回澄清请求，不能从模型文本中任意选择。

# 多轮范围继承
<!-- knowledge-id: scope.conversation.safe-inheritance -->
“该区域”“这个桩群”“上一轮”等指代只能继承服务端会话中已确认的范围。用户本轮明确给出的区域、城市、桩群和时间优先，实际查询仍要执行区域权限校验。
