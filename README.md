# Home Session / Дом за 15 минут

Original offline Android household planner: editable rooms and chores, repeat intervals and local last-completion dates, priority-first sessions for5/15/30minutes, reasons and excluded tasks, individual completion/undo, persistent resume and dated history. Kotlin native views; Android7+ (min24, compile/target36); RU/EN. No account, backend, cloud sync or purchases.

The plan is read-only until the user starts it. Only marking an individual task completed writes a completion. Finishing/cancelling never marks pending items done. The documented priority-first greedy planner keeps the estimated total within budget, admits boundary-length tasks and returns honest no-due/no-fit outcomes. See [planner invariants](docs/planner.md).

Yandex Mobile Ads8.5.0 is the only ad network: debug/CI demo banner, release guarded genuine R-M block, saved contextual/personalized choice before manual initialization, contextual default, location and SDK ad analytics off, AD_ID permission removed. Banner is outside the scrollable task controls. A finite retry policy and watchdog cannot affect household records.

GitHubActions AndroidCI runs meaningful unit tests and lint, then API24/36 journeys with partial completion/undo, real process force-stop/resume/history, RU/EN and system text200%. Signed release requires the same commit to have passed every CIjob; it verifiesAPK/AABsignatures, public cert, package/version/min/target and16KBalignment. Signed artifacts and repository releases are candidates; Play production is verified separately.

Release signing credentials are unique HOME_SESSION_ secrets and remain outside source. Privacy/support pages: https://jonkryl.github.io/home-session/privacy/ . Store/declaration preparation in docs; prepared files are not Console submission receipts.

Support: jonkryl@gmail.com
