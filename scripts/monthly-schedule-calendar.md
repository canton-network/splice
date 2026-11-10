# Monthly Splice release schedule — calendar view

Generated from `make_schedule` in `scripts/monthly-schedule.py` (branch `isegall/dev-events`).

- **Week 0** is the week of the first Monday of the target month.
- Each patch `0.X.n` is cut on a Thursday, goes to DevNet the following Monday, to TestNet a week later and to MainNet two weeks later.
- Events marked † only exist in months with 5 Mondays, which add patch `.4`.

| Wk | Mon | Tue | Wed | Thu | Fri | Sat |
|---|---|---|---|---|---|---|
| −3 | | Dev: Cut Canton & WG release line for .0 *(if applicable)* | Dev: Splice upgrades to new Canton minor line *(if applicable)* | | | |
| −2 | Dev: Canton for .0 deployed to CILR | | | | | |
| −1 | | | Dev: Cut Canton & WG releases for .0 | Dev: **Cut .0** | | |
| 0 | DevNet → .0 | | | Dev: **Cut .1** | | |
| 1 | DevNet → .1<br>TestNet → .0 | | | Dev: **Cut .2** | | |
| 2 | DevNet → .2<br>TestNet → .1<br>MainNet → .0 | DevNet Daml models take effect<br>DevNet topology freeze | DevNet LSU | Dev: **Cut .3** | | |
| 3 | DevNet → .3<br>TestNet → .2<br>MainNet → .1 | DevNet breaking config<br>TestNet Daml models take effect<br>TestNet topology freeze | TestNet LSU | Dev: **Cut .4** † | | |
| 4 | DevNet → .4 †<br>TestNet → .3<br>MainNet → .2 | TestNet breaking config<br>MainNet Daml models take effect | | | MainNet topology freeze | MainNet LSU |
| 5 | TestNet → .4 †<br>MainNet → .3 | MainNet breaking config | | | | |
| 6 | MainNet → .4 † | | | | | |

A 4-Monday month runs from week −3 to week 5 (32 events); a 5-Monday month runs to week 6 (36 events).
The dev events in weeks −3 to −1 fall in the previous month, and weeks 4 to 6 usually spill into the next one.
