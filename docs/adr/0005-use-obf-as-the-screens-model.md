# Use OBF as the Screens model

Wingmate uses Open Board Format as the internal model for Screens as well as its interchange format, with documented Wingmate extensions where OBF lacks a required concept. Keeping one model avoids duplicate mapping and round-trip failures; the product UI still uses Screen, Page, and Button rather than file-format terms.
