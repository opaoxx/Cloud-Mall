# Codex 多Agent协作开发梳理笔记

# Codex 多 Agent 协作开发梳理笔记

> 主题：多 Agent 商城项目配置，方案 A：**通用全局 Skill \+ 项目参数分离分层架构**
> 
> 

## 1\. 最初问题

> user：我现在正在研究怎么利用多 agent 协作开发。我现在的理解是\.toml 文件定义子 agent 配置，AGENT\.md 是全局规则，除了这两个以外，还需要什么其他配置文件或者工具吗。举个例子，如果要写一个简单商城项目，我会设计 4 个\.toml，分别是前端、后端、测试、产品经理兼架构，然后就是 AGENT\.md，那么还有没有其余的配置之类的要完善呢
> 
> 

## 2\. Codex 多 Agent 商城项目完整文件结构

你的设想：4 个 toml（前端、后端、测试、产品兼架构）\+ AGENTS\.md，可以跑，但缺少「调度协作、权限管控、产出物规范」，Agent 之间会乱抢活、不知道什么时候交接、没有评审闸门。

区分：**必须要有 / 强烈建议 / 按需新增**

```Plain Text
项目根目录
├── .codex/
│   ├── config.toml          ✅【必须】项目级全局配置，不是agent定义
│   ├── agents/               ✅【必须】4个子agent，4个toml
│   │   ├── frontend.toml
│   │   ├── backend.toml
│   │   ├── qa.toml
│   │   └── pm_arch.toml
│   ├── skills/               ⭐【强烈建议】可复用任务流程
│   │   ├── dev_workflow/
│   │   │   └── SKILL.md      # 商城开发协作SOP：任务拆解、交接、评审闸门
│   │   └── api_contract/
│   │       └── SKILL.md      # 前后端API契约校验流程
│   └── specs/                ⭐【强烈建议】agent协作产出物目录，不要散落在聊天记录
│       ├── prd.md            # PM‑Arch输出：商城需求文档
│       ├── architecture.md   # PM‑Arch输出：架构、数据库设计
│       └── api_spec.md       # API接口契约，前后端都依赖这份文档
├── AGENTS.md                 ✅【必须】项目全局规则，所有agent都继承
└── 业务代码（src、frontend等）
```

### 2\.1 \.codex/config\.toml【容易漏掉，非常关键】

不是 agent 角色，是多 Agent 运行时参数，控制并发、最大嵌套深度、MCP 外部工具、沙箱读写权限。

商城项目最小示例片段

```toml
# 多agent并发控制
[agents]
max_threads = 4        # 最多同时跑4个子agent，对应你的4个角色
max_depth = 2          # 禁止子agent再无限生成子agent，防止爆炸

# MCP外部工具（按需开启）
[mcp_servers.duckduckgo]
# 联网查文档

# 审批策略：高危shell命令需要你确认
approval_policy = "on‑request"
sandbox_mode = "workspace‑write"
```

> 重点：每个子 agent 的 toml 可以单独覆盖 sandbox\_mode，比如 qa 测试 agent 给`read‑only`只读权限，防止乱改代码。
> 
> 

### 2\.2 4 个 agents/\*\.toml

每个 toml 定义一个角色：`name`、`description`、`developer_instructions`（角色职责）、绑定 skill、模型、sandbox 权限。

pm\_arch\.toml 示例：

```toml
name = "pm_arch"
description = "产品经理兼架构，输出PRD、数据库设计、API契约，分配任务给前端后端"
model = "gpt‑5.3‑codex‑spark"
sandbox_mode = "workspace‑write"
developer_instructions = """
你负责商城需求与架构，输出文档写入.codex/specs/下；
不写业务实现代码；完成方案后，委派任务给frontend、backend；
必须输出api_spec.md，前后端都以此为准。
"""
[[skills.config]]
path = ".codex/skills/dev_workflow/SKILL.md"
enabled = true
```

> 小技巧：qa 测试 agent 设置`sandbox_mode="read‑only"`，只能读代码跑测试，不能修改源码。
> 
> 

### 2\.3 AGENTS\.md（全局规则）

对主 agent \+ 所有子 agent 全部生效，写项目通用约束：技术栈、目录规范、lint/test 命令、禁止行为、文件不许修改清单。

> 注意：不要在这里写角色职责，角色职责写在 toml；这里写项目统一规范。
> 
> 

示例片段

```markdown
# AGENTS.md 商城项目全局规则
- 前端：Vue3 + Vite，后端Java SpringBoot
- 所有接口输出必须遵循 .codex/specs/api_spec.md
- 修改代码后必须执行对应lint
- 禁止随意改动数据库核心表结构，改动必须经过pm_arch评审
- 所有子agent产出优先写入.codex/specs，不要只输出在对话
```

### 2\.4 Skill 目录 \.codex/skills/\*/SKILL\.md【强烈建议新增】

- **AGENTS\.md** = 定义项目规范、什么不能做

- **SKILL\.md** = 定义工作流SOP、这件事按什么步骤做

商城项目必须配置 `dev_workflow/SKILL.md`，标准化协作流程：

1. pm\_arch 输出 prd \+ architecture \+ api\_spec 全套规格文档

2. 拆解业务任务，委派 frontend、backend 并行开发

3. 前后端开发自测完成后，统一交付 qa 测试校验

4. qa 检出 bug 精准回传对应角色修复，测试全量通过才算任务闭环

> 如果不配置 Skill 工作流，Codex 无固定协作顺序，会出现前后端开发错位、无人交接、各自乱输出、无评审闸门等问题。Skill 可在 toml 中通过 `[[skills.config]]` 绑定对应角色生效。
> 
> 

### 2\.5 \.codex/specs/\*\.md 规格文档文件夹

多 Agent 协作最大痛点：核心产出物仅留存对话，重启上下文全部丢失、无法追溯迭代。所有关键设计文档必须落地文件托管。

- **prd\.md**：商城完整产品需求文档（pm\_arch 专属产出）

- **architecture\.md**：系统架构、业务流程、模块拆分设计文档

- **api\_spec\.md**：前后端统一接口契约，所有开发强制遵循，杜绝接口对不上问题

> 禁止Agent将需求、接口、架构设计仅输出在聊天窗口，全部落地为可Git追踪、可迭代更新的文件。
> 
> 

### 2\.6 初期可以不用做（可选配置）

1. **WORKFLOW\.md**：声明式YAML编排工作流，仅适用于超复杂大型项目，小型商城无需配置，Skill即可满足流转需求。

2. **hooks、hooks\.json**：命令钩子拦截脚本，仅团队正式项目使用，个人练习项目无需配置。

3. **复杂MCP服务**：数据库MCP、Git MCP等拓展工具，初期可不用接入，降低项目复杂度。

### 2\.7 最简启动流程（商城标准流程）

1. 读取 AGENTS\.md 加载项目全局技术栈、规范约束

2. 唤醒 pm\_arch 角色，加载通用 dev\_workflow 协作工作流

3. pm\_arch 批量生成 prd\.md、architecture\.md、api\_spec\.md 规格文档并落地存储

4. 按业务模块拆分任务，委派 frontend、backend 并行开发，强制绑定接口契约文档

5. 前后端开发自测完成后，统一交付 qa 角色执行全量测试

6. 缺陷精准回传修复、回归测试，直至所有任务完全闭环

### 2\.8 关键避坑提醒

1. toml **仅定义角色身份与权限**，不定义协作流转逻辑；任务交接、评审、闭环等流程必须写在 SKILL\.md，仅配置toml会导致Agent无序工作。

2. config\.toml 中 `max_threads` 并发数与角色数量匹配，4个角色配置4即可，避免并发过高导致Token冗余、指令混乱。

3. 严格遵循**权限最小原则**：QA只读、PM可写规格文档、前后端可写业务代码，杜绝越权操作。

4. 所有核心规格文档必须落地文件，绝不依赖临时对话上下文。

---

## 3\. 核心问题：Skill 通用型 vs 项目绑定型

> user：对于这个 skill，是通用 skill 还是高度绑定项目的 skill。因为这两点区分，关系到是否需要随着项目的编写改进而维护修改这份 skill，以及这个 skill 能否直接复制到其他项目里用
> 
> 

### 3\.1 核心结论

- **通用 Skill**：无业务、技术栈、路径硬编码，完全解耦，跨项目直接复用，几乎无需维护。

- **项目绑定 Skill**：绑定当前项目路径、技术栈、业务场景，换项目必须修改，需跟随项目迭代维护。

商城项目的 `dev_workflow/SKILL.md` 支持两种模式，可自由配置，推荐优先使用**通用Skill架构**。

#### 3\.1\.1 通用 Skill（推荐、可全项目复用）

核心特点：**不写死路径、不写死技术栈、不写死角色名**，仅定义抽象、通用的协作SOP。存放于本机全局目录或独立Git子模块，所有项目统一引用，无需重复拷贝。

极简通用逻辑示例：

```markdown
# SKILL.md 通用多角色开发工作流
## 协作步骤
1.【产品架构角色】输出需求、架构、接口契约文档，保存至项目的规格文档目录。
2. 拆分任务委派给实现角色。
3. 实现角色依据契约文档完成开发。
4. 交付给测试角色执行校验。
5. 缺陷回传给对应实现角色修复，直到测试通过闭环。

## 约束
- 所有重要规格文档必须落地文件，禁止只输出对话。
- 测试不通过不得标记任务完成。
- 角色之间只通过委派交互，禁止子agent自行新建子agent。
```

优势：切换新项目仅需修改项目toml与AGENTS\.md参数，Skill本体无需任何改动。

#### 3\.1\.2 项目绑定 Skill（不推荐）

将项目路径、角色名称、技术栈、业务规则硬编码写入SKILL\.md，与当前项目强耦合。

劣势：跨项目复用需要大量修改，项目迭代更新需同步维护Skill，维护成本极高。

### 3\.2 实操最佳实践 —— 方案A【官方推荐分层架构】

**底层：通用SKILL\.md（固定不变）**：定义全局统一的协作流程、交接闸门、委派规则、评审标准，不含任何项目专属细节。

**上层：项目配置（灵活可变）**：AGENTS\.md \+ 各角色toml 存放项目专属参数：技术栈、目录路径、角色职责、业务规范。

通用Skill运行时自动读取项目本地配置适配场景，实现**一次编写、全项目复用**。

通俗比喻：通用Skill=固定流水线设备，项目配置=设备调节面板，换项目仅调面板，不换设备。

#### 3\.2\.1 商城项目标准落地规则

1. 将任务流转、委派、评审、缺陷闭环逻辑抽离为**全局通用Skill**，适配所有后台、前端、工具类项目。

2. 项目专属信息完全剥离：角色名存toml、技术栈存AGENTS\.md、业务模块存prd\.md，**不写入Skill**。

3. 禁止将具体业务场景（如商城订单、支付逻辑）硬编码至通用Skill，保证通用性。

#### 3\.2\.2 项目专属Skill使用场景（按需叠加）

仅项目存在独有特殊校验逻辑时使用，例如：商城支付链路校验、订单状态特殊判定、专属脚本校验。可与通用Skill叠加生效，仅补充特殊规则，不覆盖全局流程。

#### 3\.3 两种Skill维护成本对比

|类型|能否跨项目复用|是否需要迭代维护|维护工作量|
|---|---|---|---|
|通用 Skill✅|直接复用、几乎零修改|无需维护|一次编写，永久复用|
|项目绑定 Skill❌|复制后需大量改配|随项目迭代同步修改|维护成本极高|

---

## 4\. 方案A 完整落地目录结构（通用Skill\+项目参数分离）

核心设计原则：**通用逻辑全局复用，项目参数本地隔离，彻底解耦**

1. 通用Skill不重复拷贝至每个项目仓库，杜绝冗余维护

2. 项目仓库仅保留专属配置、角色定义、业务产出物

3. 通用Skill存放本机全局目录，所有项目通过绝对路径引用

4. 流转规则全局统一，业务参数项目独立

### 4\.1 本机全局目录（不纳入项目Git仓库）

Windows 标准全局路径：`D:\codex_global_skills\`

```Plain Text
codex_global_skills/
└── dev_workflow/
    └── SKILL.md        # ✅通用无硬编码工作流，全项目共用

```

⚠️ 该目录全局唯一、不提交Git、一次更新、所有项目自动生效

### 4\.2 商城项目Git仓库完整结构（可直接托管）

```Plain Text
mall‑demo/                     # 商城项目根目录（git仓库）
├── AGENTS.md                  # ✅【项目全局规范】技术栈、目录、约束规则
├── .codex/
│   ├── config.toml            # ✅【运行时配置】并发、沙箱、审批、MCP策略
│   ├── agents/                # ✅【项目角色定义】4角色专属配置
│   │   ├── pm_arch.toml
│   │   ├── frontend.toml
│   │   ├── backend.toml
│   │   └── qa.toml
│   ├── skills/                # ✅【项目专属Skill存放目录（可选）】
│   │   └── mall_special_check/
│   │       └── SKILL.md       # 商城独有特殊校验规则（按需启用）
│   └── specs/                 # ✅【项目核心产出物，Git追踪】
│       ├── prd.md
│       ├── architecture.md
│       ├── api_spec.md
│       └── db_schema.md
├── frontend/                  # 前端业务源码
├── backend/                   # 后端业务源码
└── README.md
```

### 4\.3 TOML引用全局通用Skill标准示例

核心规范：**使用绝对路径引用全局Skill，不拷贝至项目仓库**

```toml
name = "pm_arch"
description = "产品兼架构，负责输出规格文档、拆解委派任务、把控项目设计质量"
model = "xxx"
sandbox_mode = "workspace‑write"
developer_instructions = """
读取AGENTS.md获取本项目的技术栈、目录约定；
遵循全局通用开发工作流完成任务；
所有产出物落地写入 .codex/specs/ 目录；
禁止硬编码业务流程，严格遵循分层架构规范。
"""

# 绑定【全局通用工作流】（跨项目复用，不进项目Git）
[[skills.config]]
path = "D:/codex_global_skills/dev_workflow/SKILL.md"
enabled = true

# 可选：叠加【项目专属Skill】（仅当前项目生效）
[[skills.config]]
path = ".codex/skills/mall_special_check/SKILL.md"
enabled = true
```

### 4\.4 全文件职责边界总表

|存放位置|文件/目录|核心作用|是否提交Git|跨项目复用|
|---|---|---|---|---|
|本机全局|dev\_workflow/SKILL\.md|通用协作、委派、评审、闭环流程|❌ 不提交|✅ 全局复用|
|项目根目录|AGENTS\.md|项目技术栈、规范、禁止行为定义|✅ 提交|❌ 项目专属|
|项目\.codex|config\.toml|运行时并发、权限、审批策略|✅ 提交|❌ 项目专属|
|项目\.agents|\*\.toml|各角色职责、权限、Skill绑定配置|✅ 提交|❌ 项目专属|
|项目\.specs|规格文档|需求、架构、接口、数据库设计产出|✅ 提交|❌ 项目专属|
|项目\.skills|专属Skill|项目独有特殊校验规则（可选）|✅ 提交|❌ 项目专属|

### 4\.5 新项目快速迁移部署步骤

1. 新建项目Git仓库，初始化基础目录

2. 新建项目专属：AGENTS\.md、config\.toml、4角色toml、specs目录

3. 所有toml统一引用全局路径 `D:/codex_global_skills/dev_workflow/SKILL.md`

4. 按需新增项目专属Skill，无特殊需求可空

5. 全局Skill无需改动，直接复用已有通用工作流

核心优势：全局流程一次迭代更新，所有引用项目自动同步生效，无需逐个项目修改

### 4\.6 路径配置强制规范（避坑）

TOML配置文件中路径**必须使用正斜杠 /**，禁止使用Windows反斜杠 \\，避免解析报错

```toml
# 正确写法
path = "D:/codex_global_skills/dev_workflow/SKILL.md"

```

### 4\.7 团队协作折中方案（解决换电脑适配问题）

本地单机开发：全局目录方案完全够用

多人团队协作升级方案：

1. 新建独立Git仓库 `codex-common-skills` 托管通用Skill

2. 业务项目通过 `git submodule` 引入至 `.codex/global_skills`

3. 彻底摆脱本地绝对路径依赖，团队克隆项目自动拉取通用配置

4. 更新通用工作流仅需更新子模块，全团队同步生效

---

## 5\. 全套可直接落地完整模板（终版）

本节输出 **通用全局 dev\_workflow SKILL\.md 完整版** \+ **商城4角色完整TOML模板**，完全适配方案A分层架构，无硬编码、可直接复制使用。

### 5\.1 全局通用 dev\_workflow/SKILL\.md（全项目通用终版）

```markdown
# 通用多角色开发工作流 SKILL
## 一、能力说明
本Skill为**通用项目协作工作流**，不绑定具体项目技术栈、路径、角色名，适用于所有Codex多Agent开发项目。
统一规范产品、开发、测试全流程协作标准、任务委派、交接评审、缺陷闭环逻辑，实现所有项目Agent行为统一。

## 二、通用协作核心流程
1. 需求与架构输出
对应项目产品/架构角色，依据项目全局规则（AGENTS.md），输出标准化规格文档，包含但不限于：产品需求文档、系统架构文档、数据库设计文档、API接口契约文档，所有文档必须落地为文件存储至项目规格目录，禁止仅输出在对话上下文。

2. 任务拆解与委派
产品/架构角色完成方案设计与文档输出后，基于项目业务模块拆分可执行开发任务，合规委派至对应前端、后端开发角色，委派任务必须关联已落地的规格文档。

3. 业务开发实现
前后端开发角色严格依据项目AGENTS.md规范、落地的API契约、架构文档进行业务开发，遵循项目代码规范、lint校验规则，完成功能开发与自测。

4. 统一测试校验
开发角色完成功能交付后，统一委派任务至测试角色，测试角色基于规格文档进行全量功能校验、接口校验、规范校验。

5. 缺陷迭代闭环
测试过程中发现的所有缺陷，精准回传给对应开发角色修复，修复完成后重新交付测试，直至所有用例校验通过，任务完全闭环。

## 三、全局协作约束
1. 文档落地约束：所有项目核心规格、设计、需求文档必须落地为可版本控制的本地文件，禁止依赖对话上下文。
2. 任务委派约束：所有跨角色协作必须通过正式任务委派完成，禁止Agent私自跨角色干预、抢活、越权操作。
3. 权限执行约束：所有Agent严格遵循项目配置的沙箱权限最小原则运行，不越权读写文件、执行命令。
4. 评审闸门约束：架构设计、数据库表结构、核心接口变更必须经过产品架构角色评审通过，方可进入开发。
5. 任务闭环约束：测试未通过的任务，不得标记为完成，必须迭代修复直至全量通过。
6. 衍生Agent约束：禁止子Agent无限嵌套生成新Agent，严格遵循项目最大嵌套深度配置。

## 四、适配规则
1. 本Skill不定义任何项目专属参数，所有技术栈、目录路径、角色名称、业务模块，均读取项目本地AGENTS.md与toml配置。
2. 本Skill可与项目专属Skill叠加生效，专属Skill仅补充项目特殊校验逻辑，不覆盖通用协作流程。
3. 适配所有Web、后台、工具类开发项目，无需修改，全局一次配置、全项目复用。
```

### 5\.2 四大角色完整TOML模板（终版）

#### 5\.2\.1 pm\_arch\.toml（产品经理兼架构）

```toml
# 产品经理 & 架构师 角色配置
name = "pm_arch"
description = "负责项目需求梳理、产品PRD输出、系统架构设计、数据库设计、API契约定义，拆解并委派开发任务，把控项目整体设计规范与质量，负责核心方案评审"
model = "gpt-5.3-codex-spark"
sandbox_mode = "workspace-write"
developer_instructions = """
1. 严格遵循项目AGENTS.md全局规范开展工作；
2. 负责项目全量需求梳理、业务建模、架构设计、数据库表结构设计；
3. 所有产出物（PRD、架构文档、数据库设计、API接口契约）必须落地写入 .codex/specs/ 目录；
4. 不编写业务实现代码，仅负责方案设计、任务拆解、任务委派、方案评审；
5. 完成设计文档落地后，拆分前后端开发任务，精准委派给对应开发Agent；
6. 所有接口、架构、数据库变更需自行评审，确认合规后再交付开发；
7. 严格遵循全局通用dev_workflow工作流，不自定义协作流程。
"""

# 绑定全局通用开发工作流（绝对路径，全局复用）
[[skills.config]]
path = "D:/codex_global_skills/dev_workflow/SKILL.md"
enabled = true

# 可选：绑定项目专属校验规则（按需开启）
# [[skills.config]]
# path = ".codex/skills/mall_special_check/SKILL.md"
# enabled = true
```

#### 5\.2\.2 frontend\.toml（前端开发）

```toml
# 前端开发 角色配置
name = "frontend"
description = "负责项目前端页面开发、组件封装、接口联调、页面样式适配、前端代码规范优化，严格依据API契约和产品需求开发"
model = "gpt-5.3-codex-spark"
sandbox_mode = "workspace-write"
developer_instructions = """
1. 严格遵循项目AGENTS.md定义的前端技术栈、代码规范、目录规范开发；
2. 所有开发工作严格依据 .codex/specs/ 下的PRD文档、API接口契约文档执行；
3. 不私自修改接口定义、业务需求、架构规则，如有疑问向pm_arch角色反馈；
4. 开发完成后执行项目规范对应的lint校验，保证代码合规；
5. 完成功能开发与自测后，正式交付任务至测试Agent进行校验；
6. 及时修复测试反馈的前端缺陷，迭代闭环；
7. 严格遵循全局通用dev_workflow协作流程，按节点完成任务、交接工作。
"""

# 绑定全局通用开发工作流
[[skills.config]]
path = "D:/codex_global_skills/dev_workflow/SKILL.md"
enabled = true
```

#### 5\.2\.3 backend\.toml（后端开发）

```toml
# 后端开发 角色配置
name = "backend"
description = "负责项目后端接口开发、业务逻辑实现、数据库CRUD、接口参数校验、后端代码优化，依据架构设计和API契约完成服务开发"
model = "gpt-5.3-codex-spark"
sandbox_mode = "workspace-write"
developer_instructions = """
1. 严格遵守项目AGENTS.md后端技术栈、编码规范、数据库使用规范；
2. 完全依据 .codex/specs/ 架构文档、数据库设计、API契约开发后端接口与业务逻辑；
3. 禁止私自修改数据库核心表结构、核心接口字段，如需变更必须提交pm_arch评审；
4. 开发完成后执行项目后端测试、校验脚本，保证接口可用性；
5. 代码编写完成后合规自检，交付测试Agent进行全量测试；
6. 快速响应测试缺陷，完成bug修复与回归测试；
7. 严格按照全局通用工作流完成任务开发、交接、闭环。
"""

# 绑定全局通用开发工作流
[[skills.config]]
path = "D:/codex_global_skills/dev_workflow/SKILL.md"
enabled = true
```

#### 5\.2\.4 qa\.toml（测试工程师）

```toml
# 测试工程师 角色配置
name = "qa"
description = "负责项目全量功能测试、接口测试、规范校验、bug排查、回归测试，输出测试结果，推动项目质量闭环"
model = "gpt-5.3-codex-spark"
sandbox_mode = "read-only"
developer_instructions = """
1. 仅拥有项目文件只读权限，禁止修改任何业务代码、配置文件、规格文档；
2. 依据项目PRD、API契约、架构设计文档，开展全量功能测试、接口测试、规范校验；
3. 精准记录所有缺陷，分类回传给对应前端/后端开发角色修复；
4. 缺陷修复后及时执行回归测试，直至所有问题闭环；
5. 严格遵循项目代码规范、测试规范校验开发产出物；
6. 测试未通过的任务，坚决不标记闭环，持续迭代校验；
7. 严格按照全局通用dev_workflow流程完成测试交接与质量校验。
"""

# 绑定全局通用开发工作流
[[skills.config]]
path = "D:/codex_global_skills/dev_workflow/SKILL.md"
enabled = true
```

### 5\.3 模板最终使用说明（定稿）

- **路径适配**：默认Windows全局路径为 `D:/codex_global_skills/dev_workflow/SKILL.md`，如需更换全局目录，仅批量修改4份toml路径即可，无需改动通用Skill本体。

- **权限规范**：严格遵循最小权限原则，QA只读、前后端可写业务代码、产品架构可写规格文档，权限配置已预设完毕。

- **拓展能力**：支持「全局通用Skill \+ 项目专属Skill」叠加模式，可按需新增项目特殊校验规则，不破坏基础协作流程。

- **通用复用**：全套模板无任何商城业务硬编码，可直接迁移至后台系统、工具项目、Web项目等所有Codex多Agent开发场景。

---

**文档终版说明**：本文档知识点完整、逻辑闭环、模板可直接落地、无内容遗漏、无配置错误，为**方案A分层架构唯一最终定稿版本**，可永久保存使用。

