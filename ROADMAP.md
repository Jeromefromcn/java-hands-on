# Java / Spring / JVM 實操 Roadmap

> 一條主線項目，16 個階段，每個階段都是**能跑的增量**。
> 目的不是「學會」這些技術，而是把每一個知識點壓到
> **有代碼、有可複現的現象、有決策記錄**的狀態——
> 讓每一句關於它的論斷，都能往回指到一次真實操作。

---

## 0. 怎麼用這份手冊

### 三條規則

1. **前面的階段不依賴後面的。** 每個階段只用它之前已經存在的東西。如果某個實驗非要後面的組件才做得出來，它就被排到後面去了——這是階段順序的唯一排序依據。
2. **每階段交付三樣東西**：能跑的代碼、一段可複現的現象（日誌 / 數字 / 截圖）、一份決策記錄（`docs/decisions/<nn>-<slug>.md`，格式見下）。**只有代碼不算完成**——代碼是 AI 能寫的部分，決策不是。
3. **講不出來的才回去補。** 每階段末尾有「口頭驗收」。能不看筆記講清楚就直接過，不要從頭讀一遍。

### 決策記錄格式

每個階段一份，不超過一頁：

```markdown
# <階段名> 決策記錄

## 我選了什麼
## 我否掉了什麼，為什麼
## 我看到的現象（貼數字 / 日誌片段）
## 如果流量 × 100，這個選擇哪裏先垮
```

最後一行是關鍵。「這個方案的邊界在哪」比「這個方案是什麼」難答得多，也值錢得多。

### 範圍之外

本 roadmap 只管**寫得出來、跑得起來、能觀察到現象**的東西。演算法練習、
純白板系統設計、領域知識的閱讀，都不在這裏，各自有各自的地方。

### spec-driven：流程本身就是交付物

每個階段都按同一套流程走，**而且流程本身要能展示**：

1. `docs/specs/<nn>-<slug>.md` — 要解決什麼、邊界條件、驗收標準。動手前提交
2. 實施計劃，提交
3. 然後才讓 AI 生成代碼
4. 審查分兩類：**樣板與 CRUD 完全信任，資金計算與併發邏輯逐行審**

至少挑兩個階段（建議階段 2 的金額精度、階段 8 的併發）留下完整的
「spec → plan → code → 一次 AI 偏離規格被攔下」記錄。這是本 repo 除了代碼
以外最有價值的產出。

### 環境

Docker Compose 隨便用。本地常駐一套：Postgres、Redis、Kafka、Prometheus、Grafana、Tempo/Jaeger。
Testcontainers 用於自動化測試，Compose 用於手動實驗——兩者不混用，測試不依賴常駐容器。

---

## 主線項目：證券借貸台賬（Securities Lending Ledger）

業務刻意選得很窄，窄到兩小時能講完，但每一個技術階段都能從它裏面找到真實問題。
選金融領域是因為它對正確性的要求天然嚴格——精度、併發、審計、對賬，
每一條都不是可以「差不多就行」的。

### 領域模型（六個概念，不再多）

| 概念 | 說明 | 它為哪個技術階段供料 |
|---|---|---|
| `Security` 證券 | ISIN、幣種、當前市價 | 快取（價格讀多寫少）、外部行情源 |
| `Counterparty` 對手方 | 借方，有信用額度 | 授權、額度校驗 |
| `LoanPosition` 借貸頭寸 | 某對手方借了某證券 N 股，未歸還餘額、費率、抵押品 | **樂觀鎖**、併發更新、聚合根 |
| `LoanTrade` 交易 | 借出 / 歸還，帶交易日與結算日 | **冪等鍵**、`java.time` 工作日曆、T+2 |
| `AccrualEntry` 計息流水 | 每日按未歸還餘額計費 | **批處理**、`BigDecimal` 精度、事務傳播 |
| `MarginCall` 保證金通知 | 市值重估後抵押品不足 | **事件驅動**、訊息、通知 |

### 核心用例（五個）

1. **開倉** — 對手方借入 N 股，校驗信用額度，生成頭寸與交易，帶冪等鍵
2. **歸還** — 部分 / 全部歸還，更新未歸還餘額（併發點）
3. **日切計息** — 遍歷所有活躍頭寸，按當日未歸還餘額 × 費率記一筆流水（批處理點）
4. **市值重估** — 按最新市價重算抵押品充足率，不足則發 margin call（事件點）
5. **查詢** — 頭寸列表、流水明細、分頁（性能點）

### 一個貫穿全程的約束

**金額一律 `BigDecimal`，且每個金額欄位都必須標明幣種與精度。** 這條從階段 2 定下，
之後任何階段引入的新組件（序列化、快取、訊息、資料庫）都要回頭驗證它有沒有被破壞。
這是整條主線唯一的「橫切不變式」。被追問「你怎麼在一個系統裏保證資料一致」，
答的不是 ACID，是這種東西。

---

## A 段 · 地基（不依賴 Spring）

這一段結束時你有一個純 Java 的領域核心，`domain/pom.xml` 裏沒有一行 Spring。
這不是潔癖：Spring 進來之後，很多 Java 本身的問題會被框架掩蓋，先在乾淨環境裏踩一遍。

### 階段 1 · 工程骨架

> **構建工具用 Maven。** 受監管行業（傳統金融、交易所、清算機構）的 Java 技術棧
> 壓倒性是 Maven——內部 artifact 治理、依賴審計與合規掃描的 pipeline 都是圍繞 Maven 生態建的。
> 更根本的理由是：Gradle 的 build script 是圖靈完備的代碼，構建過程本身可以有任意邏輯；
> 在要求「構建可重現、構建腳本可被合規審查」的環境裏，這是缺點不是優點。
> 本 repo 選 Maven 是為了貼近這類環境；階段 1 保留一個 Gradle 對照練習。

**做什麼**

- Maven 多模組（aggregator POM + 六個子模組）：`domain`（純 Java）、`application`（用例）、`adapter-persistence`、`adapter-web`、`batch`、`bootstrap`
- 父 POM 的 `dependencyManagement` 統一版本；子模組的 `<dependency>` 一律不寫 `<version>`
- Java 版本固定（建議 21 LTS，虛擬線程與 pattern matching 都穩定；25 的新特性在階段 8 單獨驗）
- `.editorconfig`、Spotless / google-java-format、`.gitignore`（`*.hprof`、`target/`、GC log 除外——文本輸出要提交）
- Maven Wrapper（`./mvnw`）入庫，鎖定 Maven 版本
- Git：一個邏輯變更一個 commit，commit message 英文

**實操練習**

- [x] **BOM 導入**：引入 `spring-boot-dependencies` 作為 BOM（`<scope>import</scope>`），對比「繼承 `spring-boot-starter-parent`」的做法。**受監管環境大多不能繼承外部 parent**（父 POM 要是公司自己的），所以 import BOM 才是真實寫法——寫下兩者差別
- [ ] **依賴範圍**：跑 `./mvnw dependency:tree`，看清 `compile` / `provided` / `runtime` / `test` 的傳遞規則。故意把 `domain` 的一個依賴標成 `compile`，觀察它怎麼傳遞洩漏到 `adapter-web` *(partial: the compile-scope leak was shown inside `domain` via `bannedDependencies`, not traced into `adapter-web`)*
- [x] **依賴衝突**：手動引入同一個庫的兩個版本，用 `dependency:tree -Dverbose` 找出 Maven 的「最近優先」仲裁結果。再用 `<exclusions>` 與 `dependencyManagement` 兩種方式各修一次。**這是階段 15 那個 `NoSuchMethodError` 的前置**
- [x] **`maven-enforcer-plugin`**：加規則——禁止依賴收斂衝突（`dependencyConvergence`）、禁止 SNAPSHOT 依賴、鎖定 JDK 與 Maven 最低版本。違反就 fail build
- [x] **模組邊界**：用 enforcer 的 `bannedDependencies` 檢查 `domain` 模組不引入任何第三方依賴
- [x] **profiles**：`local` / `ci` 兩個 profile，驗證 profile 激活的幾種方式與優先級
- [x] **可重現構建**：設 `project.build.outputTimestamp`，連續兩次 `clean package` 對比 jar 的 checksum。**這在受監管環境是合規要求，不是潔癖**
- [ ] **Gradle 對照（半天）**：同一套多模組結構用 Gradle + Version Catalog 再寫一遍。寫決策記錄《Maven vs Gradle：什麼場景選哪個》——重點答構建可審計性與增量構建速度的取捨，不要答「Gradle 更現代」 *(deferred indefinitely — see `docs/decisions/01-project-skeleton.md`)*

**口頭驗收**

- 講清 Maven 的依賴仲裁規則（最近優先），以及它和 Gradle 的「最高版本優先」為什麼會導致同一份依賴清單在兩邊解析出不同結果。
- 被問「你為什麼用 Maven 不用 Gradle」，能從構建腳本的可審查性答，而不是「公司規定」。

---

### 階段 2 · 純 Java 領域模型

**做什麼** — 把六個領域概念寫成純 Java 類型，一行框架註解都沒有。

**實操練習**

- [x] **不可變性**：`Money` 用 `record`（amount + currency），所有運算返回新實例；加法遇到幣種不同直接拋異常
- [x] **精度**：故意用 `double` 實現一次計息，跑 365 天累加，對比 `BigDecimal` 版本的差額。把差額數字寫進決策記錄——這是最容易講出實感的一個數字
- [x] **捨入策略**：`RoundingMode` 選哪個？在哪一步捨入（每筆捨 vs 最後捨）？寫下選擇與理由
- [x] **`sealed interface`**：`LoanEvent` 封閉為 `Opened` / `Returned` / `Accrued` / `MarginCalled`，用 pattern matching switch 處理，驗證編譯器的窮盡性檢查
- [x] **`equals`/`hashCode`**：給 `LoanPosition` 一個值物件 ID，故意寫錯 `hashCode`（只用部分欄位），放進 `HashMap` 觀察撈不回來的現象
- [x] **`java.time`**：實現 T+2 結算日計算，要跳過週末與香港公眾假期。這裏會逼你面對 `LocalDate` vs `ZonedDateTime` vs `Instant` 的選擇——業務日期用哪個、時間戳用哪個，寫下來
- [x] **異常體系**：定義 `DomainException` 分支（額度不足、頭寸不存在、歸還超額）。checked vs unchecked 選哪個、為什麼——這是高頻追問
- [x] **`Optional`**：只在返回值用，不當欄位、不當參數。故意違反一次，看它怎麼污染調用方
- [x] **Stream**：用 `Collectors.groupingBy` + `teeing` 做一次頭寸按對手方的彙總，對比 for 迴圈版本的可讀性
- [x] **設計模式落地**：不要為了用而用，從本項目的真實需求出發挑四個——
  - **策略**：計息規則（固定費率 / 階梯費率 / 浮動）
  - **狀態機**：`LoanPosition` 的 `OPEN → PARTIALLY_RETURNED → CLOSED`，非法轉換直接拋異常
  - **建造者**：`LoanTrade` 欄位多且有約束，對比 `record` 全參構造的可讀性
  - **模板方法 vs 策略**：日切與重估兩個批次的共同骨架該用哪個，寫下理由
  每個寫一句「不用它會變成什麼樣」——**答得出反面才算懂**
- [x] **資料結構選型**：頭寸按到期日排序取最近 N 筆——`TreeMap` / `PriorityQueue` / 排序後的 `List` 各寫一版，貼出三組耗時與各自的適用邊界

**口頭驗收**：5 分鐘講清「為什麼金融系統不用 `double`」，能舉出自己跑出來的那個差額數字。

---

### 階段 3 · 測試地基

排在 Spring 之前，因為之後每個階段的驗收都靠它。

**實操練習**

- [x] JUnit 5：`@Nested` 組織測試、`@ParameterizedTest` + `@CsvSource` 驗計息表、`@DisplayName` 寫成業務句子
- [x] AssertJ：自定義 `MoneyAssert`，讓斷言讀起來是 `assertThat(fee).isEqualTo("12.34 HKD")`
- [x] Mockito：先寫一個**過度 mock** 的測試（mock 掉領域物件），再改成只 mock 邊界。把兩版都留著，決策記錄裏寫「什麼時候 mock」
- [x] ArchUnit：寫三條規則（跑在 `maven-failsafe-plugin` 的集成測試階段）——`domain` 不依賴 `adapter`、`adapter-web` 不直接依賴 `adapter-persistence`、所有 `@Transactional` 只出現在 `application` 層。讓它進 CI
- [x] JaCoCo：綁到 `verify` 階段設覆蓋率門檻（`jacoco-maven-plugin` 的 `check` goal），但**在決策記錄裏寫下你為什麼設在這個數字**，以及覆蓋率高但測試沒用的一個具體例子
- [x] 測試替身分類：寫一份 `docs/decisions/test-doubles.md`，用本項目的具體例子區分 stub / mock / fake / spy
- [x] **BDD**：用 Cucumber 寫**一個** feature 就夠——「對手方借入 1000 股，三天後歸還 400 股，計息應為 X」的 Given-When-Then。目的不是鋪開 BDD，是讓你被問到時能說「我跑過，它和普通單測的分工是⋯⋯」而不是只有概念
- [x] **測試金字塔比例**：寫下本項目單元 / 集成 / 端到端的目標比例，以及**金融系統為什麼集成測試佔比通常更高**（對應 06 的過關線）

**口頭驗收**：「你怎麼決定一個東西要不要寫測試」——能從行為 vs 實現的角度答，不是背覆蓋率。

---

## B 段 · Spring 核心機制

### 階段 4 · IoC 容器

**做什麼** — 引入 Spring Boot，把階段 2 的用例接上容器。功能上不新增任何東西，純粹是換裝配方式。

**實操練習**

- [x] **Bean 生命週期**：寫一個實現 `BeanNameAware` + `InitializingBean` + `@PostConstruct` + `SmartInitializingSingleton` 的 bean，打印調用順序，畫出完整生命週期圖
- [x] **`BeanPostProcessor`**：自己寫一個，給所有帶自定義註解的 bean 包一層計時代理。這是理解 AOP 底層的最短路徑
- [x] **`@Configuration` 的 `proxyBeanMethods`**：寫一個 `@Bean` 方法調用另一個 `@Bean` 方法的場景，分別設 `true`/`false`，觀察實例數量差異。**這是高頻追問且大多數人答不出來**
- [x] **循環依賴**：故意造一個構造器注入的循環，看它啟動失敗；改成 setter 注入看它成功；寫下為什麼 Spring Boot 2.6 之後默認禁止，以及三級快取解決的是哪一種
- [x] **作用域**：把「當前請求的對手方上下文」做成 request scope，再把它注入一個 singleton，觀察需要 `ObjectProvider` 或 scoped proxy 才行的原因
- [x] **條件裝配**：用 `@ConditionalOnProperty` 做「行情源：mock / 真實」兩套實現切換；自己寫一個 `@Conditional` 實現
- [x] **配置綁定**：`@ConfigurationProperties` + `@Validated` 綁定計息規則（費率上下限、捨入精度），啟動時校驗失敗直接拒絕啟動
- [x] **Profile**：`local` / `test` / `prod` 三套，驗證配置優先級（命令列 > 環境變數 > `application-{profile}.yml` > `application.yml`）。**動手驗，不要背順序表**

**口頭驗收**：不看筆記畫出 Bean 生命週期；說清 `proxyBeanMethods=false` 什麼時候不能用。

---

### 階段 5 · AOP 與事務

這是整份 roadmap 裏回報率最高的一段——單位時間內能講清楚的東西最多。

**實操練習**

- [x] **代理機制**：同一個服務分別用「實現接口」與「不實現接口」兩種寫法，打印 `getClass()`，看 JDK 動態代理與 CGLIB 的差別；把 `proxyTargetClass` 切換一次
- [x] **自調用失效**：在一個 service 裏，`methodA()` 直接調用本類的 `@Transactional methodB()`，製造一次資料不一致。**存檔現象**。然後用三種方式修（拆類 / `AopContext.currentProxy()` / 注入自己），寫下各自代價
- [x] **傳播行為**：用「日切計息」造一個真實場景——批次裏某一筆頭寸計息失敗，不能讓整批回滾。用 `REQUIRES_NEW` 實現，並驗證它確實開了新連接（打印連接池活躍數，觀察連接翻倍的風險）
- [x] **`REQUIRES_NEW` 的坑**：外層事務持有行鎖，內層 `REQUIRES_NEW` 想更新同一行 → 自我死鎖。**造出來，存檔堆疊**
- [x] **隔離級別**：Postgres 下分別在 `READ COMMITTED` 與 `REPEATABLE READ` 跑一次「讀取餘額 → 計算 → 寫回」，造出 lost update；再用樂觀鎖修。寫下「金融場景為什麼常要求更高隔離」的真實理由（不是背定義）
- [x] **`rollbackFor`**：默認只對 unchecked 回滾——用 checked 異常造一次「異常拋了但事務提交了」，存檔
- [x] **事務 + 異步**：在 `@Transactional` 方法裏 `@Async` 調用另一個方法讀剛寫入的資料，造出讀不到的現象。這直接引出下一條
- [x] **`TransactionSynchronizationManager`**：用 `afterCommit` 回調修上面那個問題（事務提交後才發事件），這是階段 10 Outbox 的前置
- [x] **`TransactionTemplate`**：把一個聲明式事務改成編程式，寫下什麼時候該用編程式（答案與「事務邊界要比方法邊界細」有關）
- [x] **AOP 執行順序**：自定義註解 `@Idempotent` + `@Transactional` 疊在同一方法，用 `@Order` 控制誰在外層。**冪等檢查必須在事務外還是內？** 這題自己推一遍再驗

**口頭驗收**：5 分鐘講清 `@Transactional` 自調用為什麼失效（從 AOP 代理原理切入，不是「因為沒走代理」這種同義反覆）。

---

### 階段 6 · 持久層

**做什麼** — 頭寸與流水落 Postgres。Testcontainers 起庫，Flyway 管 schema。

**實操練習**

- [x] **Flyway**：三個版本的遷移——建表、加欄位、加索引。故意寫一個**不向後相容**的遷移（改欄位類型），寫下藍綠部署下它為什麼不能這樣發，正確的三步走（加新欄位 → 雙寫 → 刪舊欄位）是什麼
- [x] **N+1**：查 100 個頭寸各自的流水，開 `show-sql` 看它發 101 條。分別用 `JOIN FETCH`、`@EntityGraph`、`@BatchSize` 修，對比 SQL 條數與結果正確性（`JOIN FETCH` + 分頁的陷阱要親自踩）
- [x] **懶加載異常**：在 controller 序列化時觸發 `LazyInitializationException`；不要用 `open-in-view` 掩蓋它——把 `spring.jpa.open-in-view` 關掉，改用 DTO 投影。寫下為什麼默認開著是個壞默認
- [x] **一級快取**：同一事務內查兩次同一 ID，只發一條 SQL；修改實體不調 `save()` 也會被寫回（dirty checking）——造一次「不小心改了實體導致意外更新」
- [x] **樂觀鎖**：`@Version` 加在 `LoanPosition`。兩個線程同時歸還，一個拿到 `OptimisticLockException`。實現重試（配合 `@Retryable` 或手寫），驗證重試必須在事務**外**
- [x] **悲觀鎖**：同一場景改用 `SELECT ... FOR UPDATE`，對比吞吐量。兩組數字進決策記錄。寫下「什麼場景選悲觀」
- [x] **對照實現**：同一個「頭寸分頁查詢」用 JPA 與 `JdbcClient` 各寫一遍，對比生成的 SQL 與代碼量。決策記錄寫「這個項目哪些場景該繞開 ORM」
- [x] **執行計劃**：造 50 萬行流水，跑一個沒索引的範圍查詢，`EXPLAIN ANALYZE` 存檔；加複合索引後再存一次。**注意複合索引欄位順序**，故意做反一次看它不走索引
- [x] **連接池**：HikariCP 池大小設成 2，跑併發請求，觀察 `Connection is not available` 超時。寫下池大小怎麼估（與 CPU 核數、事務時長的關係）
- [x] **手寫複雜 SQL**：白板題會考，ORM 練不到。至少寫三條——
  - 窗口函數：每個對手方按頭寸市值排名，取前三（`ROW_NUMBER` vs `RANK` vs `DENSE_RANK` 的差別要能當場說）
  - 遞歸 CTE：抵押品池的層級歸集
  - 自連接 + 日期區間：算每個頭寸的連續持有天數
  每條都跑 `EXPLAIN`，寫下它和等價的應用層實現相比在什麼數據量下反轉
- [x] **二級快取**：先不要開。寫下「為什麼我在這個項目裏不開 Hibernate 二級快取」——分佈式失效問題留給階段 9 的 Redis 解

**口頭驗收**：講一次自己造的慢查詢排查——從現象到 `EXPLAIN` 到索引設計，路徑要有順序。

---

### 階段 7 · Web 層

**實操練習**

- [ ] **REST 設計**：五個用例的 endpoint。開倉是 `POST /loans`，歸還是什麼？（`POST /loans/{id}/returns` vs `PATCH /loans/{id}`——選一個並寫下理由）
- [ ] **冪等鍵**：開倉帶 `Idempotency-Key` header，重複請求返回同一結果而不是建兩個頭寸。用 Redis 或資料庫唯一約束實現，**寫下併發同時到達時的競態處理**
- [ ] **Bean Validation**：`@Valid` + 自定義約束（ISIN 校驗碼、費率範圍）；分組驗證（開倉 vs 歸還規則不同）
- [ ] **全局異常處理**：`@RestControllerAdvice` 統一返回 RFC 7807 `ProblemDetail`。領域異常 → 4xx，未預期異常 → 5xx 且不洩漏堆疊
- [ ] **Jackson**：`BigDecimal` 序列化成字串而不是數字（JS 精度問題），`LocalDate` 格式統一。**造一次前後端精度丟失**再修
- [ ] **分頁**：`Pageable` + 總數查詢。造 50 萬行，對比 offset 分頁在深頁的劣化，實現一次 cursor 分頁，貼兩組耗時
- [ ] **Filter vs Interceptor vs AOP**：三層各打一條日誌，打印執行順序，畫圖。寫下「traceId 該在哪一層生成」（這決定階段 12 的 tracing 能不能串起來）
- [ ] **`RestClient`**：調用外部行情源。設超時（連接 / 讀取分別設）、故意讓對方 hang 住，觀察沒設超時時線程池被打爆的過程——**這是階段 11 熔斷的動機，先把痛感造出來**
- [ ] **OpenAPI**：springdoc 生成文檔；寫一條 contract 測試驗證實際響應符合 schema

**口頭驗收**：講清 Filter / Interceptor / AOP 各自能拿到什麼、拿不到什麼，以及你在本項目裏怎麼分工。

---

**B 段結束檢查**：此時應有一個能跑的單體服務，五個用例全通，Testcontainers 集成測試綠燈。
回頭驗證橫切不變式：金額經過 HTTP 序列化 → JPA → Postgres → 再讀回來，精度與幣種有沒有丟。

---

## C 段 · 分佈式能力

### 階段 8 · 併發

**做什麼** — 用階段 7 那個「慢外部行情源」當真實下游，做線程池實驗。

**實操練習**

- [ ] **線程池耗盡**：`ThreadPoolExecutor` 有界隊列 + 慢下游，打到拒絕策略觸發。`jstack` 輸出存檔，**標出哪幾行讓你判定是池滿而不是死鎖**
- [ ] **四種拒絕策略**：`Abort` / `CallerRuns` / `Discard` / `DiscardOldest`，本項目選哪個、為什麼。`CallerRuns` 的反直覺後果（把壓力推回 Tomcat 線程）要親自造出來
- [ ] **池參數**：核心數 / 最大數 / 隊列長度三者的關係——故意設一個「隊列很長所以最大線程數永遠用不到」的配置，觀察它
- [ ] **`CompletableFuture`**：並行拉多個證券的價格再彙總。踩一次「沒指定 executor 用了默認 ForkJoinPool.commonPool」的坑；踩一次異常被吞（沒有 `exceptionally` / `join` 時異常去哪了）
- [ ] **`ThreadLocal` 洩漏**：用 `ThreadLocal` 存請求上下文，線程池復用導致上下文串到下一個請求。**造出串號現象**，再用 `try/finally` 清理修好。順帶驗 `InheritableThreadLocal` 在線程池下為什麼不管用
- [ ] **AQS / CAS**：用 `AtomicReference` + CAS 實現一次頭寸餘額的無鎖更新，對比 `synchronized` 版本；在高衝突下 CAS 自旋反而更慢——把兩組數字跑出來
- [ ] **虛擬線程**：把處理慢下游的部分切到虛擬線程，對比吞吐量。然後在虛擬線程裏用 `synchronized` 包住阻塞呼叫造出 **pinning**，用 `-Djdk.tracePinnedThreads=full` 存檔，改成 `ReentrantLock` 再測一組
- [ ] **JMM 與指令重排序**：用經典的雙線程 flag 例子造出可見性問題（跑夠多輪才會出現，這本身就是要講的點——**偶發性正是它難查的原因**）。加 `volatile` 修好，寫下 happens-before 規則在這裏具體是哪一條
- [ ] **`volatile` 不保證原子性**：多線程 `volatile int count++` 造出丟失更新，再換 `AtomicInteger`。這題問得極頻繁，**必須有自己跑出來的數字**
- [ ] **雙重檢查鎖定**：寫一個沒有 `volatile` 的 DCL 單例，說清「部分構造物件」怎麼洩漏出去。修好
- [ ] **JMH**：用 `jmh-maven-plugin` 或獨立 `benchmarks` 子模組，對計息計算做一次基準測試。至少踩一次「忘了 `Blackhole` 導致 JIT 把代碼優化掉，得到荒謬的數字」
- [ ] **併發集合**：`ConcurrentHashMap` 的 `computeIfAbsent` 在遞歸調用下死鎖——造一次

**口頭驗收**：「延遲在凌晨三點突然抬高，講一遍排查」——給出有順序的路徑：先看什麼、據此排除什麼、再看什麼。

---

### 階段 9 · 快取

**做什麼** — 證券價格讀多寫少，是天然的快取對象。

**實操練習**

- [ ] **本地快取**：Caffeine 做價格快取，設 `maximumSize` + `expireAfterWrite`。**先故意不設上限**，配合階段 15 的 heap 實驗埋雷
- [ ] **Spring Cache 抽象**：`@Cacheable` / `@CacheEvict` / `@CachePut`。踩一次自調用失效（與階段 5 同源，驗證你真的懂代理）
- [ ] **分佈式快取**：切到 Redis，序列化方式選型（JDK / JSON / Protobuf），**驗證 `BigDecimal` 經過序列化後精度沒丟**
- [ ] **穿透**：查不存在的 ISIN，每次都打到庫。用空值快取 + 布隆過濾器各修一次，對比代價
- [ ] **擊穿**：熱點 key 過期瞬間大量請求打穿。用互斥重建修，寫下「為什麼不用邏輯過期」或反之
- [ ] **雪崩**：大量 key 同時過期。加隨機 TTL 修，**貼出修之前的 QPS 尖峰圖**
- [ ] **一致性策略**：價格更新時是「先更新庫再刪快取」還是「先刪再更新」？造出兩種順序各自的不一致窗口，寫下延遲雙刪的代價與你的選擇
- [ ] **分佈式鎖**：日切計息只能有一個實例跑。用 Redis `SET NX PX` 手寫一次（含 Lua 腳本保證釋放的原子性），再換 Redisson 對比。**手寫版本必須能回答：持有者 GC 停頓超過 TTL 時會怎樣**
- [ ] **fencing token**：把上一條的答案做出來——模擬持有者 STW 超過 TTL，鎖被第二個實例拿走，**兩個實例同時在寫**。造出重複計息的現象，再用單調遞增的 fencing token 讓下游拒絕舊 token 的寫入。這是「用過分佈式鎖」和「知道它的邊界」的分水嶺
- [ ] **續期（watchdog）**：Redisson 的看門狗解決什麼、不解決什麼。寫下為什麼它不能替代 fencing token
- [ ] **多級快取**：本地 + Redis 兩級，本地失效怎麼通知（Redis pub/sub）。這是階段 10 的前置練習

**口頭驗收**：講清一個快取不一致的具體窗口——時間軸上誰先誰後，不是「可能會不一致」。

---

### 階段 10 · 訊息

**做什麼** — 市值重估產生 margin call，用事件發出去；價格更新從 Kafka 消費。

**實操練習**

- [ ] **Spring 應用事件**：先用 `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)` 做進程內事件。驗證它解決了階段 5 那個「事務未提交就發事件」的問題
- [ ] **Kafka 生產**：價格更新事件。分區鍵選 ISIN——寫下為什麼，以及選錯（比如用隨機鍵）會破壞什麼
- [ ] **順序性**：同一證券的兩次價格更新亂序到達，造出「舊價格覆蓋新價格」。用分區鍵 + 版本號兩種方式修
- [ ] **冪等消費**：同一條消息重複投遞。用消費端去重表修，**去重鍵的 TTL 設多久**——這題直接對應你已有的 story 10（去重鍵永不過期），把現象重現一次
- [ ] **offset 提交**：自動提交 vs 手動提交，造一次「提交了 offset 但處理失敗」的消息丟失，和一次「處理成功但沒提交」的重複消費。兩個現象都存檔
- [ ] **重試與死信**：`DefaultErrorHandler` + 指數退避 + DLT。寫下「哪些異常該重試、哪些直接進死信」——這是決策不是配置
- [ ] **Outbox 模式**：開倉時要同時寫庫 + 發事件。先造出「庫寫成功但 MQ 發失敗」的不一致，再用 outbox 表 + 輪詢發送修好。**這是分佈式事務題的標準答案，必須自己跑過**
- [ ] **Saga**：開倉的完整流程是「校驗額度 → 凍結抵押品 → 建立頭寸 → 通知對手方」，任一步失敗要補償。用編排式（orchestration）實現一次，畫出補償路徑。**對比 Outbox 解決的是不同層面的問題**，寫下來
- [ ] **TCC 三坑**：把「凍結抵押品」做成 Try-Confirm-Cancel，然後逐個造出來——
  - **空回滾**：Try 還沒到，Cancel 先到了
  - **懸掛**：Cancel 執行完之後，遲到的 Try 才到
  - **冪等**：Confirm 重複投遞
  三個現象各存一份，再用「事務狀態表 + 唯一約束」統一修好
- [ ] **2PC 為什麼不用**：不必實現，但要寫下阻塞問題的具體場景（協調者在 prepare 之後掛了，參與者的鎖要持有多久）以及它和 Saga 的取捨
- [ ] **決策文檔**《客戶保證金變動的一致性方案選型》：為什麼不能用簡單的 `UPDATE balance = balance - x`，至少三種方案的取捨
- [ ] **消費者組與 rebalance**：起三個實例再殺掉一個，觀察 rebalance 期間的處理停頓，寫下它對 SLA 的影響
- [ ] **背壓**：生產快於消費，觀察 lag 增長。`max.poll.records` 與 `max.poll.interval.ms` 的關係——造一次「處理太慢被踢出消費者組」

**口頭驗收**：白板畫出 Outbox 的完整流程，包括「輪詢器自己掛了」怎麼辦。

---

### 階段 11 · 批處理

> **未驗證的判斷待覆核**：`docs/decisions/02-domain-model.md` 的「Unverified,
> carried to stage 11」一節，對日切計息與市值重估這兩個批次的共同骨架，暫定
> 用組合策略而非模板方法繼承。寫這個 Job/Step/Chunk 時回頭對照，看暫定判斷
> 站不站得住，並把結論寫回該決策記錄。

**做什麼** — 日切計息 + 市值重估，用 Spring Batch 正式化。

**實操練習**

- [ ] **Job / Step / Chunk**：計息 Job，`ItemReader`（分頁讀活躍頭寸）→ `ItemProcessor`（算費用）→ `ItemWriter`（批量寫流水）。chunk size 調三組，貼耗時對比
- [ ] **失敗重啟**：跑到一半 kill 掉，重啟驗證它從斷點繼續而不是從頭。**看 `BATCH_STEP_EXECUTION` 表裏的實際記錄**，不要只信它說重啟成功了
- [ ] **冪等**：同一天跑兩次計息，不能記兩筆。用唯一約束（頭寸 + 業務日期）實現，驗證第二次跑是跳過而不是報錯
- [ ] **skip 與 retry**：單筆頭寸資料異常時跳過而不中斷整批，設 skip 上限。與階段 5 的 `REQUIRES_NEW` 對照——**Spring Batch 的 skip 和你自己寫的 `REQUIRES_NEW` 解決的是不是同一個問題**，寫下來
- [ ] **分區**：按對手方分區並行跑，對比單線程耗時。踩一次「分區鍵分佈不均導致某個分區拖尾」
- [ ] **JobParameters**：業務日期當參數傳入，支持補跑昨天。驗證 Spring Batch 用參數做 job instance 唯一性識別的機制
- [ ] **監聽器**：`JobExecutionListener` 記錄批次指標，接到階段 12 的 Micrometer
- [ ] **對賬批次**：這是獨立的第三個 Job，不是計息的附屬。造一份「外部託管方持倉快照」（CSV 或另一張表），與本系統頭寸比對——
  - 差異分類：己方多、對方多、雙方都有但數量不符、雙方都有但金額不符
  - 每類的處理路徑不同：寫下哪些自動補償、哪些必須人工介入、閾值定在哪
  - **故意造出四類差異各一筆**，驗證它們被正確分類
  - 寫下「為什麼對賬對 Prime Brokerage 比對交易所更重要」（對應 02 與 07 第 5 題）
- [ ] **補償觸發**：結算失敗後的補償機制——觸發條件、重試次數、升級路徑。與階段 10 的 Saga 補償對照，寫下兩者的邊界
- [ ] **決策文檔**：《為什麼用 Spring Batch 而不是自己寫定時任務》——重點答「重啟語義」與「執行歷史可審計」，不要答「因為它是標準」

**口頭驗收**：被問「一個批處理任務中斷後怎麼安全重跑」，從冪等設計直接切入。

---

## D 段 · 生產化

### 階段 12 · 韌性與可觀測

**實操練習**

- [ ] **Resilience4j 熔斷**：包住階段 7 那個慢行情源。讓它真的打開——貼出「熔斷前 / 半開 / 關閉」三態的狀態轉換日誌。**閾值怎麼定**寫進決策記錄
- [ ] **限流 + 艙壁**：`RateLimiter` 與 `Bulkhead`。艙壁的價值在階段 8 已經造過痛感（一個慢下游打爆整個池），這裏是修好它
- [ ] **降級**：熔斷後返回快取價格而不是報錯，並在響應裏標明資料是 stale 的。**降級不能悄悄降**——這是金融系統的原則
- [ ] **超時預算**：從 HTTP 入口到資料庫，逐層列出超時值，驗證它們是遞減的。造一次「外層超時比內層短導致重試風暴」
- [ ] **Actuator**：`/health` 的 liveness 與 readiness 分開。**造一次「readiness 誤報導致滾動更新把流量打到未就緒實例」**
- [ ] **Micrometer**：業務指標（開倉數、margin call 數、計息批次耗時）+ 技術指標。接 Prometheus，Grafana 出一塊板
- [ ] **告警設計**：寫下三條告警規則，每條都要是**結果導向**（margin call 積壓 > N 分鐘）而不是原因導向（CPU > 80%）。這是你已有的 SRE 實踐，這裏是把它落成配置
- [ ] **分佈式追蹤**：OpenTelemetry + Tempo/Jaeger。驗證 traceId 能從 HTTP 穿過 Kafka 到消費端——**跨消息邊界的 context 傳播要手動接**，這是會卡住的地方
- [ ] **結構化日誌**：JSON 格式、traceId 入 MDC、線程池切換時 MDC 丟失的問題（用 `TaskDecorator` 修）
- [ ] **日誌脫敏**：對手方名稱與金額在日誌裏怎麼處理。寫下規則

**口頭驗收**：講清「告警應該告結果而不是原因」，用本項目的具體規則舉例。

---

### 階段 13 · 安全

刻意排在這裏：它會改寫請求鏈路，早放會讓 B 段的調試多一層噪音。

**實操練習**

- [ ] **Spring Security 過濾鏈**：打印完整的 filter 列表，理解它與階段 7 的 Filter / Interceptor 的相對位置
- [ ] **認證**：JWT resource server，用本地 Keycloak（Compose 起）發 token
- [ ] **授權**：方法級 `@PreAuthorize`——對手方只能看自己的頭寸。**造一次越權**（改 URL 裏的 ID 看到別人資料）再修好
- [ ] **與事務的順序**：`@PreAuthorize` 與 `@Transactional` 誰先執行？推一遍再驗
- [ ] **審計**：誰在什麼時候開了哪個倉，寫審計表。驗證審計記錄在主事務回滾時的行為（該不該一起回滾）
- [ ] **常見漏洞**：故意寫一個字串拼接的 SQL 查詢，用 `'; --` 打穿，再改 prepared statement；驗證一次 mass assignment（請求體直接綁實體，客戶端塞進 `id` 欄位）
- [ ] **密鑰管理**：所有密鑰走環境變數，驗證它們不會出現在 `/actuator/env` 與日誌裏
- [ ] **依賴掃描**：OWASP Dependency-Check 或 Trivy 進 CI

**口頭驗收**：講清你的授權模型——「誰能對什麼做什麼」，以及它在代碼裏落在哪一層。

---

### 階段 14 · 打包與交付

**實操練習**

- [ ] **Docker 分層**：用 Spring Boot layered jar，驗證只改一行業務代碼時只有最後一層變化（對比鏡像層 digest）
- [ ] **啟動優化**：測基線啟動時間 → 開 CDS → 開 AOT，三組數字。**寫下每一步的代價**（AOT 會禁掉什麼）
- [ ] **GraalVM native**：編出來，測啟動時間與記憶體。記錄反射配置踩到的具體問題。寫下「這個項目該不該上 native」——大概率答案是不該，理由要具體
- [ ] **配置外部化**：同一鏡像跑三個環境，零重新打包。驗證配置優先級（與階段 4 呼應）
- [ ] **優雅停機**：`server.shutdown=graceful`。**造一次「停機時正在處理的請求被切斷」**再修好；驗證 Kafka 消費者停機時 offset 的處理
- [ ] **探針**：liveness / readiness / startup 三種，配 K3s deployment，滾動更新時零 5xx——用壓測工具持續打流量驗證
- [ ] **JVM 參數基線**：容器裏的堆大小怎麼定（`MaxRAMPercentage` 而不是固定 `-Xmx`），驗證容器感知在你的 JDK 版本下確實生效
- [ ] **CI pipeline 本身**：前面各階段都寫「進 CI」，這裏把它真的搭起來——`mvn verify` → ArchUnit + JaCoCo 門檻 → enforcer → 依賴掃描 → 構建鏡像 → 推送。**每一道門禁失敗時的阻斷行為都要驗一次**，不要只看綠燈
- [ ] **質量門禁與四眼原則**：分支保護 + 必需審查。寫下「金融系統的 four-eyes principle 在 pipeline 裏落成什麼」——這是 06 的概念自測項，落成配置就答得具體
- [ ] **灰度發佈**：把生產環境的標準做法重現一次——**maker-checker**（發起與批准分離）、**批次間隔**（第一批 5% 後強制觀察 N 分鐘）、**降級開關**（配置中心一鍵回到舊邏輯）。用 feature flag 對本項目的計息規則做一次灰度切換
- [ ] **回滾演練**：發一個有 bug 的版本，計時從發現到回滾完成。寫下「為什麼金融系統的發佈比互聯網系統更保守」——**答的要是具體機制，不是態度**
- [ ] **部署上 K3s**：復用 `docker-gitops` 那套。git-first：commit → ArgoCD sync → verify

**口頭驗收**：講清容器裏 JVM 堆大小該怎麼設，以及設錯時的具體表現（OOMKilled vs `OutOfMemoryError` 的區別）。

---

## E 段 · 深水區

### 階段 15 · JVM 實操

排最後不是因為難，是因為前面 14 個階段的代碼才是它的燃料——要有真實的快取、線程池、批處理才造得出火場。

> 與 `09-core-java-jvm/` 的三個實驗有重疊。那邊是獨立小實驗，這邊是**在真實服務上**重現，
> 兩者都做，差別本身就是談資（「在玩具程式上和在有業務負載的服務上，同一個現象長得不一樣」）。

**實操練習**

- [ ] **GC 日誌判讀**：開 `-Xlog:gc*`。跑正常負載存一份，跑階段 9 那個無上限 Caffeine 快取存一份。**標出從「回收得動」到「回收不動」的轉折點**
- [ ] **堆耗盡**：讓無上限快取把堆吃光。`-XX:+HeapDumpOnOutOfMemoryError` 存 dump（**dump 不提交**），用 MAT / VisualVM 從 dominator tree 找到那個 Map，判讀筆記進 `docs/evidence/`
- [ ] **GC 收集器對比**：G1 vs ZGC，同一批處理負載，對比暫停時間分佈與吞吐量。寫下「這個項目選哪個、為什麼」
- [ ] **線程池耗盡**：階段 8 已造過，這次在完整服務上重現，`jstack` + `jcmd Thread.print` 對比
- [ ] **死鎖**：兩個頭寸互相轉移抵押品，加鎖順序相反。`jstack` 自動檢測輸出存檔
- [ ] **JFR**：開一次飛行記錄，跑一輪壓測，用 JMC 找出最熱的方法。**驗證它和你的直覺是否一致**——不一致的那次才是有價值的筆記
- [ ] **JIT**：`-XX:+PrintCompilation` 觀察計息方法何時被 C2 編譯；用 JMH 對比預熱前後。做一次去優化（讓一個一直是單態的調用點突然變多態）
- [ ] **逃逸分析**：寫一個能被標量替換的 `Money` 運算，用 `-XX:-DoEscapeAnalysis` 對照，貼分配速率差異
- [ ] **類載入**：造一次 `NoSuchMethodError`（兩個版本的同一個庫），用 `-verbose:class` 定位它從哪個 jar 載入。這是依賴衝突的標準排查
- [ ] **記憶體分區**：metaspace 耗盡造一次（動態生成類），與堆 OOM 的表現對比
- [ ] **決策記錄**：每個實驗都寫「我看到什麼 → 我怎麼判斷 → 下一步看哪裏」

**口頭驗收**：不看筆記講一次完整的 JVM 排查路徑，順序不能亂。

---

### 階段 16 · 性能與架構治理

**實操練習**

- [ ] **壓測**：k6 或 Gatling 打查詢與開倉兩條路徑。**先估算再測**——寫下你預測的 QPS 與 P99，再看實際，差距本身是最好的筆記
- [ ] **容量估算**：從業務量反推——N 個對手方 × M 個頭寸，日切要在幾分鐘內跑完，需要多少資源。這是階段 11 分區數的依據
- [ ] **瓶頸定位**：壓到瓶頸，用 USE 方法（利用率 / 飽和度 / 錯誤）逐層排查，找出到底卡在哪。至少做兩輪「找到瓶頸 → 修 → 再壓」
- [ ] **全鏈路壓測**：帶影子標識的請求走影子表，不污染真實資料。驗證影子標識能穿過 HTTP → 線程池 → Kafka → 批處理全鏈路而不丟
- [ ] **ArchUnit 補完**：把 A–E 段累積的所有架構約束固化成規則（層依賴、`@Transactional` 位置、領域層無框架依賴、DTO 不洩漏到 domain）
- [ ] **模組化**：驗證 `domain` 模組確實能脫離 Spring 單獨編譯與測試；如果不能，把違反的地方找出來
- [ ] **ADR 回顧**：把 16 份決策記錄整理成一份 `docs/decisions/README.md` 索引，每條一句話。**這份索引就是對外講述整個 repo 時的地圖**
- [ ] **一次架構反轉**：挑一個前面做過的決策（例如 JPA / 快取一致性策略 / 消息 vs 同步調用），推翻它重做一遍，寫下反轉的理由與代價。**有推翻記錄的工程師和沒有的，是兩種工程師**

---

## 進度追蹤

| 段 | 階段 | 狀態 | 決策記錄 | 口頭驗收 |
|---|---|---|---|---|
| A | 1 工程骨架 | ☑ | ☑ | ☐ |
| A | 2 領域模型 | ☑ | ☑ | ☐ |
| A | 3 測試地基 | ☑ | ☑ | ☐ |
| B | 4 IoC 容器 | ☑ | ☑ | ☐ |
| B | 5 AOP 與事務 | ☑ | ☑ | ☐ |
| B | 6 持久層 | ☑ | ☑ | ☐ |
| B | 7 Web 層 | ☐ | ☐ | ☐ |
| C | 8 併發 | ☐ | ☐ | ☐ |
| C | 9 快取 | ☐ | ☐ | ☐ |
| C | 10 訊息 | ☐ | ☐ | ☐ |
| C | 11 批處理 | ☐ | ☐ | ☐ |
| D | 12 韌性與可觀測 | ☐ | ☐ | ☐ |
| D | 13 安全 | ☐ | ☐ | ☐ |
| D | 14 打包交付 | ☐ | ☐ | ☐ |
| E | 15 JVM 實操 | ☐ | ☐ | ☐ |
| E | 16 性能與治理 | ☐ | ☐ | ☐ |

## 約定

- **決策記錄與 spec 是交付物**，不是文檔債。階段沒有決策記錄就不算完成。
- **實驗用的錯誤代碼不進 `main` 的產品路徑**，規則見 `CLAUDE.md`。
- 數字（耗時、QPS、差額、GC 暫停）一律貼實測值，不寫約數，不事後憑印象補。
