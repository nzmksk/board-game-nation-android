# Statistics

Every number the app shows, and the rule that produces it.

**This document dictates.** Where the code disagrees with anything written here, the code
is wrong and the fix belongs in its own issue — not in an edit to this page. It is a
specification of what each figure means, not a description of what the current build
happens to compute.

It covers the Statistics screen, the figures on a game's page and a player's, the numbers
the dashboard tiles show, and the metrics the achievement rules are measured against.

---

## Rules that apply everywhere

These hold for every figure below unless that figure says otherwise. They are stated once
here so each definition can be read as the one thing it adds.

### Which plays count

**A play counts when it is neither a draft nor invalid.** Those two are the only
exclusions that are universal, and they are universal without exception:

| Flag | Meaning | Why it is always out |
|---|---|---|
| `is_draft` | A session the timer opened that was never saved | Not a play yet. It may never become one. |
| `is_invalid` | The table got the game wrong — misread rules, wrong setup | A play of a game played wrongly is a play of no game at all. It is badged on the session row precisely because every statistic has already dropped it. |

Everything else that can be said about a play — that it was abandoned, that somebody was
being taught, that a rule stopped it early — really happened. Those plays are excluded
only from the particular figures they would distort, and every such exclusion is named in
the definition that makes it.

The four conditional exclusions, and what each of them means:

| Condition | Column | Excluded from |
|---|---|---|
| **Abandoned** — the table gave up | `is_incomplete = 1`, written from `end_condition = ABANDONED` | Anything with a winner in it, and anything about how long a game takes. Nobody won it, and it stopped before it was over. |
| **Ended early** — a rule in the box stopped it | `end_condition = SPECIFIC` | Score averages and personal bests only. The play has a legitimate winner, but any number recorded against it is a mid-game count. |
| **Teaching** — somebody was learning | `is_teaching_game = 1` | The non-teaching duration average, and nothing else. A teaching game is a real play that happens to run long. |
| **Cooperative** — the table shared one outcome | `is_cooperative = 1` | Every win rate about an individual, and the first-player figure. When everybody wins together, who won says nothing about any one person. |

`is_cooperative` means *the table had one result*, not *the box says co-operative*. An
investigative play writes it too.

A play whose `end_condition` is null was logged before the app asked the question. It is
treated as `STANDARD`: nobody said, which is not the same as something having gone wrong.

### Which boxes count as played

A play names one game — the base game — and the expansions that went out with it are
recorded separately. Two different questions follow from that, and they get two different
answers:

| Question | Counted over | Used by |
|---|---|---|
| **Was this box on the table, and how often?** | Every game the play involved: the one it was logged against, plus each expansion that went out with it | Every per-box figure — play count, cost per play, last played, the shelf of shame, dead weight, first and last played |
| **What was played that evening?** | The one game the play was logged against | Every ranking over plays — most played, the h-index, plays by month, plays by day, different games played |

The first is why an expansion that goes to every game night has a cost per play and is not
on the shelf of shame. The second is why one evening with three expansions out is one bar
on the "plays by month" chart rather than four.

### Which games count as owned

**Owned means `OWNED` or `LENT_OUT`.** A game at a friend's house is still a game you own
and still part of the collection's value.

`WISHLIST`, `SOLD` and `PLAYED_NOT_OWNED` are outside every collection figure. The one
exception is [spend by year](#spend-by-year), which is a record of money that left rather
than of what is on the shelf, and therefore includes what has since been sold.

### What a game has cost

**Total cost is the price of the box plus every accessory line against it** — sleeves, an
insert, a playmat, the shipping on a preorder. Nothing that divides or sums a cost uses
the price column alone.

**A game with no price and no accessories has no cost — it is unpriced, not free.** It is
excluded from cost figures rather than counted as zero. A game with accessories but no
price does have a cost, and gets one.

Accessories carry no currency of their own: they are bought in the same money as the game
they belong to. Sums are therefore only meaningful within one currency, and the app makes
no attempt to convert between them.

### Rounding and presentation

Percentages are shown as whole numbers, everywhere, without exception. Any comparison
between two percentages — the first-player edge, most obviously — is computed from the
unrounded figures and rounded once at the end, so it is never the difference of two
roundings.

Currency is shown to the collection's default currency's own precision. Durations are
stored in whole minutes and shown in hours where an hour reads better than ninety minutes.

### What a win is

`is_winner` is set on a participant row by whichever rule the play's scoring mode implies,
and every win rate in the app counts those rows:

| Scoring | Who wins |
|---|---|
| **Ranked scores** | Everyone placed first. Ties are genuine: two players on the same score are both first, and the next player is third. Players with no score entered are neither placed nor winners. |
| **Manual placement** | Whoever the user put at the top. Nobody else. |
| **Team-based** | Every player on the winning side. Nobody is placed — a side winning says nothing about the order inside it. Sides match case-insensitively on the trimmed name. |
| **Cooperative / objective-based** | Every player, or none, according to the table's one outcome. |
| **None** | Nobody, unless the user names a winner outright. |

Because tied players are all winners, **two players can both be winners of the same
play**. Head-to-head is the one place that matters, and it is spelled out there.

---

## Collection

The Statistics screen's first tab: what is on the shelf, independent of whether any of it
has been played.

### Games owned
Count of owned games that are not expansions.

### Expansions
Count of owned games that are expansions.

An expansion's status is its own. An expansion you own for a base game you sold is still
an owned expansion.

### Collection value
Sum of [total cost](#what-a-game-has-cost) across owned games that have one. Games with no
cost contribute nothing rather than zero — the figure is what the collection is known to
have cost, and it does not pretend to know about the boxes nobody priced.

Wishlist and sold games are outside it.

### By mechanic / By category
For each tag of that kind, the number of **distinct owned games** carrying it. Ranked by
count descending, ties broken by tag name, case-insensitively. Top 12.

Games can carry many tags, so these bars deliberately do not sum to the collection size.

### Weight spread
Owned games bucketed by BGG weight into seven bands, each half-open at the top:

`1.0–1.5`, `1.5–2.0`, `2.0–2.5`, `2.5–3.0`, `3.0–3.5`, `3.5–4.0`, `4.0+`

A game with no weight recorded is not in any band. Expansions are included: an expansion
with a weight of its own is a box with a weight.

### Player count coverage
For each head count from 1 to 8, the number of **owned base games** that support exactly
that many players — that is, where the game's minimum is at or below it and the maximum at
or above it.

A game needs both bounds recorded to be counted at any head count. Expansions are
excluded: what a table wants to know is how many games it can play, and an expansion is
not one of them.

### Shelf of shame
Owned games that have **never been on the table**, counted through
[the boxes rule](#which-boxes-count-as-played). Each carries the number of days since it
was added to the collection, and the list is ordered oldest purchase first.

An expansion that has gone out with its base game is not on this list, whatever it was
logged against.

### Owned but unrated
Count of owned games with no rating of any kind against them.

---

## Plays

The second tab: what actually happened at the table.

### Total plays
Count of qualifying plays. Abandoned plays are included — an evening that ended in
somebody giving up is still an evening.

### Total hours
Sum of every qualifying play's duration, in hours. Abandoned plays are included, for the
same reason: the time was spent.

### Different games
Count of distinct games that plays were **logged against**. This is the evening's game,
not every box that went out with it.

### Plays by month
Count of qualifying plays per calendar month of `played_on`, oldest month first. Months
with no plays do not appear.

### Plays by day
Count of qualifying plays per day of the week, Sunday first.

### Most played
The ten games with the most plays **logged against them**, ranked by count descending,
ties broken by title, case-insensitively.

Expansions do not appear here and do not add to their base game's count. One evening is
one play of the game it was logged against.

### Longest sessions / Shortest sessions
The five individual plays with the greatest and least duration. One bar per play, labelled
with the game's title; the same game can appear more than once.

Abandoned plays are excluded — a game that stopped early was not a short game, it was an
unfinished one.

### Actual length vs publisher label
For each game with at least **2** qualifying, complete, non-teaching plays and both
playtime bounds recorded:

- **Actual** — the mean duration of those plays
- **Stated** — the midpoint of the game's own playtime range, `(min + max) / 2`

Ranked by the absolute gap between the two, largest first. Top 10.

Teaching games are excluded here specifically, because the question is how long the game
takes once the table knows it.

The stated range is whatever is on the game's record — written by a BGG import, or typed
in by hand. It is the publisher's label rather than any one source's claim.

### Week streak
Two numbers over the set of distinct weeks that saw at least one play:

- **Current** — the run of consecutive weeks ending at this week or last week. Zero if the
  most recent playing week is older than that.
- **Longest** — the longest such run ever.

A week is anchored to its start date, so two plays in one week count once and a run
crosses a year boundary without resetting at week 52. A streak that is alive is one you
have this week or can still save this week; one that ended last month is not.

### Day streak
The same over consecutive days. The current run is alive if the most recent playing day is
today or yesterday.

Used by the achievement rules rather than shown on the Statistics screen.

### Month streak
The same over consecutive calendar months. Achievements only.

### H-index
**The largest N such that at least N games have been played at least N times.**

An h-index of 7 means seven games with seven or more plays each. It is the single number
that separates a collection that gets played from one that gets bought: it cannot be
raised by one obsession or by a wide shelf of single plays, only by depth across breadth.

Counted over the games plays were **logged against**. A shelf with no plays on it has an
h-index of 0.

Games tied on a play count all support the index equally: three games played twice each
support an h-index of 2. Ties are the normal shape of a real shelf rather than an edge
case, since most games on one share a play count with several others.

---

## Value

The third tab: what the collection cost against what it has given back.

### Cost per play
For one box: its [total cost](#what-a-game-has-cost) divided by the number of qualifying
plays **it was on the table for**.

This is the definition everywhere the phrase appears — the collection list, a game's page,
the Value tab and the achievement rules all mean exactly this. In particular it divides
total cost and not the price, and it counts plays through
[the boxes rule](#which-boxes-count-as-played) so that an expansion has one.

A game needs a cost above zero and at least one play to have a cost per play. A game with
neither is not infinitely expensive; it simply has no figure, and sorts last.

It is computed from the first play onwards. A game bought last week and played once has a
cost per play, and it is a large one — that is the figure doing its job.

### Best value / Worst value
The five owned games with the lowest and highest cost per play.

### Overall cost per play
The whole collection's [total cost](#what-a-game-has-cost) divided by the number of
**box-plays** it has produced.

Both halves have to be counted the same way, which is the whole subtlety here. Every
priced box contributing to the numerator contributes the plays it was on the table for to
the denominator, expansions included. An evening with a priced base game and three priced
expansions out is four box-plays.

Counting that evening once while all four prices stayed in the total would make the
average worse the more of the collection actually got played.

Only owned, priced games are in either half.

### Paid for, never played
Owned games with a cost above zero that have never been on the table. Ordered by cost
descending, top 5.

The same question as the shelf of shame, asked in money.

### Spend by year
Sum of [total cost](#what-a-game-has-cost) grouped by the year the game was added to the
collection.

**Sold games are included.** This is a record of money that left, and selling a game
afterwards does not unspend it. That makes this the one collection figure that is not
about what is currently on the shelf.

Accessories land in the year their game was added, because a cost line carries no date of
its own. It is the only date there is, and it keeps these bars summing to what was spent.

---

## Players

The fourth tab: who is at the table and how they do.

### Standings
Per player, over every qualifying **competitive, completed** play they were in:

| Figure | Rule |
|---|---|
| Plays | Count of those plays |
| Wins | Count of those they won |
| Win rate | Wins ÷ plays |
| Average score | Mean of their scores, over plays that have one |

Ordered by win rate descending, ties broken by plays.

Co-operative plays are excluded, as they are from every figure that ranks one person
against another: when the table wins together, counting it says nothing about who is good.
Abandoned plays are excluded because nobody won them.

### Head to head
The device owner's record against each other player, over the qualifying **competitive,
completed** plays the two of them shared.

Three outcomes, and they are about which of the pair came out ahead rather than about what
either of them did:

| Outcome | Rule |
|---|---|
| **Win** | The owner won and the opponent did not |
| **Loss** | The opponent won and the owner did not |
| **Draw** | Everything else |

A draw is therefore two things at once: a victory the pair tied for — since tied players
are both winners — and equally a play some third person took, where neither of the pair
got past the other. Both are the same answer to the question being asked, which is who
came out ahead.

**The three add up to the shared play count exactly.** That is a property worth relying
on, and it is why the count is not shown separately beside the record.

Ordered by wins descending, then by losses ascending, then by name. A 10–0 outranks a
10–5, which outranks a 10–13, and every one of those outranks a 5–3. The list is a ranking
of records, not of how much has been played.

### Nemesis
The opponent who beats the device owner most often, over at least **3** shared plays.

Three plays is the floor because two is not a rivalry. Where two opponents win at the same
rate, the one who has done it over more plays is the nemesis: 8 of 16 is a rivalry in a
way 2 of 4 is not.

---

## One game

The figures on a game's own page. Every one of them counts the plays **that box was on the
table for**, so an expansion's page describes the evenings it went out on.

### Plays
Count of qualifying plays the box was part of.

### Hours
Sum of those plays' durations. Abandoned plays included.

### Average length
Mean duration over **complete** plays.

A second average excludes teaching games as well, and is the honest answer to "how long
does this take" once everybody knows the rules.

### Shortest / Longest
The least and greatest duration among complete plays.

### First played / Last played
The earliest and latest `played_on` among qualifying plays.

### Win rate
The device owner's wins at this game, over the qualifying plays they were present for.

A co-operative play counts the table's outcome as the owner's own: in a co-op the owner
won if the table did.

### Cost per play
As [defined above](#cost-per-play): total cost over plays on the table.

### Does going first win?
Two percentages side by side, over this game's qualifying plays that recorded a turn
order:

- **Went first** — how often the player in the first seat won
- **By chance** — what the first seat would have won if going first meant nothing: per
  play, the winners divided by the players, averaged across plays

The gap between the two, in percentage points, is the finding. The bare rate answers
nothing on its own — 45% is a rout at a table of five and a losing record at a table of
two — and the chance baseline is what makes a history that mixes table sizes readable.

Four kinds of play are excluded, each because counting it would manufacture an advantage
that is not there or hide one that is:

| Excluded | Why |
|---|---|
| Co-operative | The table wins together, so the seat says nothing |
| Abandoned | Nobody won them |
| Solo | The only player also went first, and would report a permanent 100% |
| No winner recorded | Would drag the rate down for want of data rather than for want of an advantage |

A play must name exactly one first seat to qualify.

**This is only ever shown per game.** A first-player advantage is a property of a game,
not of a shelf. A collection-wide figure would average a heavy euro together with a filler
and describe neither.

### Win rate by faction
Per faction played at this game, across **everybody** who has played it: plays, wins, and
the rate.

Deliberately not per player. The question is whether the game is balanced, so Halikarnassos
winning 30% of the time is the figure no matter who was sitting behind it.

Abandoned plays are excluded — a game nobody finished has no winner, and counting it would
drag every faction down as though each had lost.

**Co-operative plays are counted here**, and this is the one win rate where they are. A
game where the table shares one result still asks a real balance question: which spirit,
which character, which role tends to be at the table when the table wins.

Factions are grouped case-insensitively, so "Alexandria" and "alexandria" are one faction.

### Rating
The game's most recent rating's computed score, on a 0–10 scale.

A rating is never updated in place. Re-rating a game a year later adds a row, and the
newest one is what every other screen means by the game's rating; the rest are history.

---

## One player

The figures on a player's own page.

### Win rate by game
Every game the player has a qualifying **competitive** play of: plays, wins, and the rate.

**There is no minimum sample.** A profile is a record of what somebody has played, and
hiding the games played once leaves a player still building a history looking at a single
bar. The sample size is shown beside every rate instead, because 100% off one play and
100% off twelve are the same number and not the same fact.

Ordered by rate descending, ties broken by plays, then by title. Five wins from five
outranks one from one.

### Average score
Per game, the mean of the player's scores, over plays that **ran to the game's own
ending**.

Plays a rule stopped early are excluded. The game ended before final scoring, so any
number against it is a partial count, and averaging it in with full scores understates the
result.

Top 10 by score. Two games' scores share no scale, so that ordering is a presentation
choice and not a ranking of anything.

### Personal best
The best score the player has recorded at each game they have scored one at, over the same
plays the average uses.

**Best is not always biggest.** A game flagged high-score-wins takes the largest number;
under golf scoring the smallest number is the good one, and the flag travels with the
figure so the screen can say which it is showing.

Ordered by how much the player has played the game, not by the score. Ranking 92 at
Wingspan against 8 at Hive would invent a comparison the data does not support.

### Personal bests set in one play
Asked of a play rather than of a player, for the shared result card: which players walked
away from this evening holding a new record at this game.

A player set one when their score in this play is **strictly better** than every other
qualifying scored play of that game they have. Two consequences worth naming:

- **Equalling a record does not set one.** A card announcing a personal best beside a
  score somebody has already made would be overstating the evening.
- **A first scored play of a game is never a best.** There was nothing to beat. That play
  is already marked on the card as a first play.

The plays that can hold a record, and the plays that can set one, are the same ones the
personal best itself is taken from.

---

## Ratings

A rating is a set of scores against one rubric's criteria, normalised onto 0–10:

```
Σ(score × weight) ÷ Σ(weight × max_score) × 10
```

summed over **only the criteria that were actually scored**. A criterion left blank is out
of both halves — it does not count as a zero.

Dividing by the weighted maximum rather than by the number of criteria is what lets two
rubrics with different criteria, different weights and different ranges produce scores
comparable with each other, which is the entire reason a computed score is stored at all.

The result is clamped to 0–10. A rating with nothing scored, or a rubric whose weighted
maximum is zero, computes to 0.

---

## Achievement metrics

The rule engine measures against one snapshot of the numbers below, taken once per
evaluation pass. Every one of them obeys
[the universal rules](#rules-that-apply-everywhere); where a metric asks whether a box has
been played, it asks through [the boxes rule](#which-boxes-count-as-played), so a
well-used expansion cannot hold the shelf-clearing achievements shut.

### Counting metrics

| Metric | Definition |
|---|---|
| `TOTAL_PLAYS` | Qualifying plays |
| `TOTAL_HOURS` | Sum of their durations, in hours |
| `GAMES_OWNED` | Owned games, expansions included |
| `DISTINCT_GAMES_PLAYED` | Distinct games plays were logged against |
| `DISTINCT_MECHANICS_PLAYED` | Distinct mechanic tags on boxes that have been on the table |
| `GAMES_TAUGHT` | Distinct games with at least one teaching play that had somebody new at it — games, not plays |
| `GAMES_RATED` | Distinct games with any rating |
| `DISTINCT_PLAYERS` | Distinct people who have been in a qualifying play |
| `WIN_STREAK` | The owner's longest run of consecutive competitive wins, in play order |
| `LOSS_STREAK` | The same for losses |

Win and loss runs read the owner's competitive results ordered by date and then by session
id. Co-operative plays are not in the sequence at all — they neither extend a run nor
break one.

### Threshold metrics

| Rule type | Measures |
|---|---|
| `PER_GAME_THRESHOLD` | The most plays any single game has |
| `TIME_WINDOW` | The most plays inside one day, week or month |
| `STREAK` | The longest run of consecutive days, weeks or months with a play |
| `ATTRIBUTE` | The largest single value ever recorded: a play's player count, a play's duration in hours, or the weight of a game that has been on the table |
| `RATIO` | The owner's best win rate at any one game with at least the rule's minimum plays. Competitive plays only. |

### Collection conditions

| Scope | Condition |
|---|---|
| `NO_UNPLAYED_GAMES` | Every owned game has been on the table. Its target is however many games are owned **today**, so buying a game legitimately re-locks it. |
| `COST_PER_PLAY_UNDER` | Some game's [cost per play](#cost-per-play) has fallen below the target |
| `MECHANIC_COMPLETED` | Every owned game carrying some one mechanic has been played, over mechanics with at least 3 owned games. A mechanic with one game is not an accomplishment. |

### How a rule is satisfied

A rule with an `AT_LEAST` comparison is satisfied when the current value reaches the
target. A rule with `AT_MOST` — cost per play, and anything else where smaller is better —
is satisfied when the value is **above zero and at or below** the target. A brand-new
collection has a cost per play of zero, and that is not an accomplishment.

An unlock lasts as long as the data supports it. Editing or deleting a play re-checks
every achievement and withdraws the ones the data no longer supports: an undeserved trophy
would make the whole screen untrustworthy.

A rule this build cannot read never unlocks, and never crashes.

---

## Dashboard

The dashboard shows no figure of its own. Each tile is one of the above:

| Tile | Defined at |
|---|---|
| Games owned | [Games owned](#games-owned) |
| Total plays | [Total plays](#total-plays) |
| Streak | [Week streak](#week-streak) |
| Overdue loans | Games lent out whose loan is older than the reminder threshold in Settings |

---

## The collection list

Each row's figures, all of them computed in the query so that the list can sort by them:

| Column | Defined at |
|---|---|
| Play count | Qualifying plays the box was on the table for |
| Last played | The latest `played_on` among those |
| Rating | [Rating](#rating) — the newest one |
| Cost per play | [Cost per play](#cost-per-play) |
| Price | The price of the box alone, deliberately — sorting by price is its own question, and not the same one as sorting by what a game has cost |

**A null sorts last in every ordering, in both directions.** An unpriced or unplayed game
arriving at the top of "cheapest per play" would be a lie, not a result.
