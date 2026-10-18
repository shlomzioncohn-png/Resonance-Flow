"""Enums used by the RawObjectEvent contract."""

from enum import Enum


class RawSourceType(str, Enum):
    """Where Bridge got the item: the wire list agreed in #31.

    Separate from SourceType, which follows the task output (Inbox), so a value
    added for one contract never leaks into the other.
    """

    EMAIL = "EMAIL"
    SLACK = "SLACK"
    WHATSAPP = "WHATSAPP"
    DRIVE = "DRIVE"
    CALENDAR = "CALENDAR"
    ZOOM = "ZOOM"
    BIGBLUEBUTTON = "BIGBLUEBUTTON"
    SCANNED_DOC = "SCANNED_DOC"


class ScopeDecision(str, Enum):
    """Bridge work-relevance decision."""

    INCLUDED = "INCLUDED"
    EXCLUDED = "EXCLUDED"


class Modality(str, Enum):
    """Technical routing hint supplied by Bridge."""

    AUDIO = "AUDIO"
    SCANNED_DOC = "SCANNED_DOC"
    TEXT = "TEXT"
    
class ObjectRole(str, Enum):
    """What one MinIO object is within its logical item."""

    BODY = "BODY"
    ATTACHMENT = "ATTACHMENT"
    TRANSCRIPT = "TRANSCRIPT"
    RECORDING = "RECORDING"
    SOURCE_METADATA = "SOURCE_METADATA"


class ChangeType(str, Enum):
    """Type of source-object change reported by Bridge."""

    UPSERT = "UPSERT"
    DELETE_REVIEW = "DELETE_REVIEW"


class DecidedBy(str, Enum):
    """Whether Bridge's classification came from a deterministic rule or a model."""

    MODEL = "MODEL"
    RULE = "RULE"


class Category(str, Enum):
    """What kind of item it is. Which values are valid depends on source_type."""

    # ZOOM / BIGBLUEBUTTON
    PLANNING = "PLANNING"
    STANDUP = "STANDUP"
    CUSTOMER_CALL = "CUSTOMER_CALL"
    INTERVIEW = "INTERVIEW"
    ONE_ON_ONE = "ONE_ON_ONE"
    SOCIAL = "SOCIAL"
    TEST = "TEST"
    # SCANNED_DOC: the 16 RVL-CDIP classes, plus BLANK
    LETTER = "LETTER"
    FORM = "FORM"
    EMAIL = "EMAIL"
    HANDWRITTEN = "HANDWRITTEN"
    ADVERTISEMENT = "ADVERTISEMENT"
    SCIENTIFIC_REPORT = "SCIENTIFIC_REPORT"
    SCIENTIFIC_PUBLICATION = "SCIENTIFIC_PUBLICATION"
    SPECIFICATION = "SPECIFICATION"
    FILE_FOLDER = "FILE_FOLDER"
    NEWS_ARTICLE = "NEWS_ARTICLE"
    BUDGET = "BUDGET"
    INVOICE = "INVOICE"
    PRESENTATION = "PRESENTATION"
    QUESTIONNAIRE = "QUESTIONNAIRE"
    RESUME = "RESUME"
    MEMO = "MEMO"
    BLANK = "BLANK"
    # Both lists
    OTHER = "OTHER"


class Quality(str, Enum):
    """How readable the item's primary object is."""

    GOOD = "GOOD"
    POOR = "POOR"
    UNREADABLE = "UNREADABLE"


class Sensitivity(str, Enum):
    """How sensitive the content is. Route sensitive items on HIGH."""

    NONE = "NONE"
    LOW = "LOW"
    HIGH = "HIGH"
