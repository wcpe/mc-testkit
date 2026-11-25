# ADR-0030：console 命令通道令牌与发送者名单双闸鉴权（协议 v2）

- 状态：已接受（取代 ADR-0027 中 v1 帧格式一段）
- 日期：2026-11-26

## 背景

ADR-0027 的 `E2E_CMD:<id>:<command>`（v1）帧不带任何凭据：桩只要收到 `mc-testkit:cmd` 通道
的插件消息就以 console 身份执行。任何能入服的玩家（含离线 serve 手测模式下的真人）都可
伪造该消息拿到控制台全权——这是真实可利用的漏洞，而非理论面。

## 决策

协议升级为 **v2**：`E2E_CMD:<token>:<id>:<command>`，回执 `E2E_CMD_DONE` 格式不变。

- **闸一（通道令牌）**：每次构建由编排层 SecureRandom 24 字节生成，经
  `MC_TESTKIT_E2E_CMD_TOKEN` 同时下发给后端桩与 bot 进程 env。桩 fail-closed：
  令牌未配置或不符即拒绝执行（不符时按解析出的 id 回 FAIL 回执；v1 帧解析不出 `<id>` 段时不回执，含冒号命令正文碰巧解析出 id 时同样只会收到令牌不符的 FAIL 回执）。
  令牌不进日志、不进回执（红线）。
- **闸二（发送者名单）**：`MC_TESTKIT_E2E_CMD_SENDERS` 下发本轮实际起的全部 bot
  进程用户名；发送者玩家名不在名单内即拒绝。serve 等无 bot 场景名单为空 → 拒绝一切
  console 命令请求（该场景下通道没有合法来源）。
- **不设 legacy 旁路**：与门禁哲学一致，v1 帧（`<token>` 段位置是 `<id>`）按令牌不符拒绝，不提供兼容开关。
- 名单与 `ops.json` 刻意不同源：`execCommand` 走服务端权限、bot 不需要 OP，许可发送者
  是「本轮起的全部 bot」，含未提权 bot 与单 bot 的 `BOT_USERNAME` 覆盖名。
- 令牌宿主是构建服务（RunOutputService）而非任务上下文：配置缓存复用时各任务持独立
  上下文副本，字段级令牌会使后端任务与 bot 任务摇出不同令牌。

## 破坏性与配套

bot-core 与 harness-core 须同版本配套使用（0.20.0 随 harness-core 0.4.0 / bot 0.3.0 发布）；
旧 bot 发 v1 帧一律被拒，升级路径是同时换依赖版本。回执与聊天通道契约不变。
