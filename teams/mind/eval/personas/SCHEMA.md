# Persona Evaluation Schema — תיעוד

**קובץ המקור המחייב:** [`scenario.schema.json`](./scenario.schema.json) (JSON Schema, draft 2020-12).
מסמך זה מסביר *למה* כל שדה קיים; ה-JSON Schema הוא מה שנאכף בפועל בקוד (`make eval`).

## עקרון יסוד

**סכמה אחת משותפת לכל שש הפרסונות** (Developer, Manager, Higher Manager, Field Engineer,
Salesperson, CEO). מה שמשתנה בין פרסונות הוא **הערכים**, לא המבנה. אסור לבנות
variant של הסכמה לתפקיד ספציפי — כל תרחיש, מכל תפקיד, הוא אובייקט `PersonaEvaluationScenario` אחד ויחיד.

## Persona ≠ Tier

זו ההפרדה הכי חשובה בסכמה: **מי האדם** (`persona.role`) הוא לא **איזה סוג משימה** (`task.tier`).
דוגמה: Developer יכול לקבל גם `Operational` וגם `Tactical` task. CEO בדרך כלל יקבל `Strategic`,
אבל הסכמה לא אוכפת את זה כחוק — זה נשאר תלוי-תרחיש, כדי שהתרחישים יוכלו לבדוק גם מקרי קצה
(לדוגמה: CEO שנתקל במשימה אופרטיבית דחופה).

## מבנה כללי

```
Scenario
├── scenario_id
├── persona        { role, seniority?, department? }
├── context         { free_time_minutes, current_datetime, time_horizon, constraints[] }
├── tasks[]          { task_id, title, description, deadline?, estimated_duration_minutes,
                        tier, blocked, dependencies[] }
├── expected_top_task_id
├── expected_reason
└── source          { type, source_detail? }
```

## תיעוד לכל שדה

### Persona

| שדה | מייצג | למה נחוץ | ערכים מותרים | חובה/אופציונלי | מי משתמש בו בהמשך | דוגמה |
|---|---|---|---|---|---|---|
| `role` | מי המשתמש שעבורו מדרגים משימות | קובע את "סוג החיים" של המשתמש — קצב, אופק זמן, סוג המשימות שרלוונטיות (טבלת התפקידים ב-MIND-01) | `Developer` / `Manager` / `Higher Manager` / `Field Engineer` / `Salesperson` / `CEO` | חובה | ה-harness (מבטיח כיסוי של כל תפקיד בסט); R2/R3 בבניית פיצ'רים ספציפיים-לתפקיד | `"Developer"` |
| `seniority` | רמת הבכירות בתוך התפקיד | לפעמים משנה את התשובה הנכונה (Junior לא ייקח משימת ארכיטקטורה); לפעמים לא — לכן אופציונלי | `Junior` / `Mid` / `Senior` / `Executive` / `null` | אופציונלי | מודל הדירוג, רק אם קיים סיגנל שתלוי בכך | `"Junior"` |
| `department` | האגף הארגוני | מבדיל בין למשל Field Engineer בתפעול לבין Sales Engineer | `Engineering` / `Sales` / `Operations` / `null` | אופציונלי (חלק מהתפקידים, כמו CEO, לא משויכים לאגף בודד) | Lens (aggregation לפי אגף בעתיד) | `"Engineering"` |

### Context

| שדה | מייצג | למה נחוץ | ערכים מותרים | חובה/אופציונלי | מי משתמש בו בהמשך | דוגמה |
|---|---|---|---|---|---|---|
| `free_time_minutes` | כמה זמן פנוי יש עכשיו | השדה המכריע ביותר בתרחיש — אותה רשימת משימות יכולה לקבל תשובה אחרת לגמרי ב-20 דקות מול שעתיים | מספר שלם חיובי | חובה | הרצת ה-harness; בעתיד Chain (הבחירה בפועל) | `30` |
| `current_datetime` | "עכשיו" של התרחיש | בלי זה אי אפשר להעריך אם `deadline` קרוב, רחוק, או כבר עבר | ISO 8601 datetime | חובה | חישוב דחיפות מול deadline | `"2026-08-23T14:00:00"` |
| `time_horizon` | טווח התכנון (היום / השבוע / החודש) | תואם את קצב העבודה השונה של כל תפקיד (Developer=שעות-ימים, CEO=רבעונים) | `today` / `this_week` / `this_month` | חובה | מודל הדירוג — אופק זמן משפיע על אילו משימות בכלל רלוונטיות | `"today"` |
| `constraints` | אילוצים נוספים שהמערכת חייבת לדעת עליהם | תופס מקרים שלא מכוסים על ידי free_time/deadline לבד (למשל "אסור לתאם פגישות") | מערך מחרוזות חופשי, ריק כברירת מחדל | חובה (המערך עצמו, גם אם ריק) | מודל הדירוג ו-Explanation generation | `["no meetings can be scheduled"]` |

### Task

| שדה | מייצג | למה נחוץ | ערכים מותרים | חובה/אופציונלי | מי משתמש בו בהמשך | דוגמה |
|---|---|---|---|---|---|---|
| `task_id` | מזהה ייחודי בתוך התרחיש | נדרש כדי ש-`dependencies` ו-`expected_top_task_id` יוכלו להצביע עליו | מחרוזת ייחודית בתרחיש | חובה | dependencies, expected_top_task_id | `"task_B"` |
| `title` | כותרת קצרה | קריאוּת אנושית + לעיתים תשומש כפיצ'ר טקסטואלי | מחרוזת | חובה | Lens (הצגה), extraction pipeline (בעתיד) | `"Review PR #482"` |
| `description` | פירוט המשימה | נותן למודל (ולבן אדם שבודק) מספיק הקשר לשפוט משך/דחיפות בלי מידע חיצוני | מחרוזת | חובה | מודל הדירוג | `"Code review for the auth refactor PR"` |
| `deadline` | מועד יעד, אם יש | קובע דחיפות ביחס ל-`current_datetime` | ISO 8601 datetime או `null` | אופציונלי (nullable) | חישוב דחיפות | `"2026-08-23T17:00:00"` |
| `estimated_duration_minutes` | כמה זמן המשימה דורשת | מושווה ישירות מול `free_time_minutes` — הבדיקה הכי בסיסית של "האם זה בכלל אפשרי עכשיו" | מספר שלם חיובי | חובה | מודל הדירוג; Chain (constraint-satisfaction) | `20` |
| `tier` | סוג המשימה (לא התפקיד!) | מפריד "מי האדם" מ"איזה סוג עבודה" — ר' סעיף Persona ≠ Tier למעלה | `Operational` / `Tactical` / `Strategic` | חובה | R2 (segmented ranking — כלל ניקוד נפרד לכל tier) | `"Operational"` |
| `blocked` | האם המשימה חסומה כרגע | משימה חסומה בדרך כלל לא אמורה להיות התשובה הנכונה | בוליאני | חובה | מודל הדירוג; Chain (בחירת המשימה בפועל) | `false` |
| `dependencies` | מזהי משימות שחייבות להסתיים קודם | מייצג את יחס ה-blocking בין משימות בתרחיש | מערך של `task_id`, ריק כברירת מחדל | חובה (גם ריק) | Chain (traversal API בעתיד) | `["task_A"]` |

### שדות ברמת התרחיש

| שדה | מייצג | למה נחוץ | ערכים מותרים | חובה/אופציונלי | מי משתמש בו בהמשך | דוגמה |
|---|---|---|---|---|---|---|
| `expected_top_task_id` | התשובה הנכונה הידועה מראש | מאפשר להשוות Model prediction מול Expected answer ולחשב metric — זה כל הרעיון של ה-harness | חייב להתאים ל-`task_id` אחד מתוך `tasks` (נאכף בקוד הוולידציה, לא ב-JSON Schema) | חובה | ה-harness (חישוב top-1 accuracy / MRR) | `"task_B"` |
| `expected_reason` | הסבר קצר ומבוסס-נתונים לתשובה הנכונה | ההבדל בין "ניחוש" לביקורת ניתנת-לבדיקה; משמש גם בהמשך להשוואת baseline מול stretch (שבוע 6–7) | מחרוזת, לפחות 20 תווים, חייבת להתבסס על שדות מהתרחיש עצמו | חובה | מנטור (בדיקת סבירות); R1 (evidence ל-baseline vs. stretch) | `"Task B fits the available 30-minute window, has a near deadline, and Task A is blocked."` |
| `source.type` | מקור התרחיש | שקיפות: נדע בעתיד אם המודל נבדק על דוגמה מומצאת או על דאטה אמיתי | `synthetic` / `real` / `AMI` / `FUNSD` / `other` | חובה | R1 (ניתוח ביצועים לפי סוג מקור); תיעוד data policy | `"synthetic"` |
| `source.source_detail` | פרטי המקור המדויקים | מאפשר לעקוב אחורה למקור בפועל (למשל meeting ID ספציפי ב-AMI) | מחרוזת חופשית או `null` | אופציונלי (בפועל נדרש כש-`type` הוא AMI/FUNSD/other) | R1 (traceability) | `"AMI meeting ES2002a"` |

## אילוצים שלא נאכפים ב-JSON Schema עצמו (נאכפים בקוד הוולידציה של R1)

- `expected_top_task_id` חייב להיות שווה ל-`task_id` של אחד מ-`tasks`.
- כל `task_id` בתוך `dependencies` חייב להתקיים בפועל בתוך אותו `tasks`.
- אין תרחישים כפולים (`scenario_id` ייחודי בכל `eval/personas/`).
- כל אחד משישה התפקידים מכוסה לפחות פעם אחת בסט המלא.
- כשל-`source.type` הערך `AMI`, `FUNSD` או `other` — יש למלא `source_detail`.

## מה עדיין לא מוגדר (במכוון)

לפי כלל השלב-10 במסמך המקורי: **אחרי שהסכמה הזו מאושרת** — ולא לפני — עוברים לכתיבת
6 התרחישים הראשונים (2 Developer, 2 Manager, 2 CEO) כבדיקת-תקינות לסכמה, ורק אז ל-24–36
המלאים. הקובץ הזה במכוון לא כולל דוגמאות תרחיש.
