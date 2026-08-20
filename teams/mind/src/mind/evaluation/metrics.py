from typing import List, Dict

def score_extraction(predicted: List[str], ground_truth: List[str]) -> Dict[str, float]:
    """
    מחשבת מדדי דיוק (Precision) וכיסוי (Recall) עבור משימות שחולצו.
    """
    # שלב 1: המרה לסטים (Sets) כדי לאפשר חיפוש חיתוכים מהיר ולמנוע כפילויות
    pred_set = set(predicted)
    truth_set = set(ground_truth)
    
    # שלב 2: טיפול במקרה קצה בו המודל לא חילץ שום משימה
    if not pred_set:
        return {"precision": 0.0, "recall": 0.0}

    # שלב 3: מציאת החיתוך (True Positives) - המשימות שהמודל זיהה נכון
    true_positives = len(pred_set.intersection(truth_set))
    
    # שלב 4: חישוב המדדים
    precision = true_positives / len(pred_set)
    recall = true_positives / len(truth_set) if truth_set else 0.0

    # שלב 5: החזרת התוצאה כמילון, עם עיגול ל-4 ספרות עשרוניות
    return {
        "precision": round(precision, 4),
        "recall": round(recall, 4)
    }