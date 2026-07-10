from pathlib import Path
from urllib.request import urlretrieve

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT, TA_RIGHT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate,
    Frame,
    Image,
    PageTemplate,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
)

OUT = Path(__file__).resolve().parent / "output"
OUT.mkdir(parents=True, exist_ok=True)

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
pdfmetrics.registerFont(TTFont("DejaVu", FONT))
pdfmetrics.registerFont(TTFont("DejaVu-Bold", FONT_BOLD))

LOGO_URL = "https://upload.wikimedia.org/wikipedia/en/3/3b/EAP_Official_Logo.png"
LOGO = OUT / "eap_logo.png"
try:
    urlretrieve(LOGO_URL, LOGO)
except Exception:
    LOGO = None

styles = getSampleStyleSheet()
body = ParagraphStyle(
    "BodyGreek",
    parent=styles["Normal"],
    fontName="DejaVu",
    fontSize=9.15,
    leading=12.0,
    alignment=TA_LEFT,
    spaceAfter=0,
)
body_bold = ParagraphStyle(
    "BodyBoldGreek",
    parent=body,
    fontName="DejaVu-Bold",
)
center = ParagraphStyle(
    "CenterGreek",
    parent=body,
    alignment=TA_CENTER,
)
header = ParagraphStyle(
    "HeaderGreek",
    parent=center,
    fontName="DejaVu-Bold",
    fontSize=9.6,
    leading=12,
)
title_style = ParagraphStyle(
    "TitleGreek",
    parent=center,
    fontName="DejaVu-Bold",
    fontSize=13.2,
    leading=16,
    spaceAfter=8,
)
comment_style = ParagraphStyle(
    "CommentGreek",
    parent=body,
    fontSize=8.65,
    leading=11.1,
    alignment=TA_JUSTIFY,
)
right = ParagraphStyle(
    "RightGreek",
    parent=body,
    alignment=TA_RIGHT,
)
right_bold = ParagraphStyle(
    "RightBoldGreek",
    parent=right,
    fontName="DejaVu-Bold",
)


def add_page(canvas, doc):
    canvas.saveState()
    canvas.setFont("DejaVu", 8)
    canvas.drawCentredString(A4[0] / 2, 10 * mm, "1")
    canvas.restoreState()


def make_form(filename, student, thesis_title, code, comments):
    path = OUT / filename
    doc = BaseDocTemplate(
        str(path),
        pagesize=A4,
        leftMargin=20 * mm,
        rightMargin=20 * mm,
        topMargin=13 * mm,
        bottomMargin=16 * mm,
        title="Αξιολόγηση Πτυχιακής Εργασίας",
        author="Παπαϊωάννου Βάιος",
    )
    frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id="normal")
    doc.addPageTemplates([PageTemplate(id="official", frames=frame, onPage=add_page)])

    story = []
    if LOGO and LOGO.exists():
        img = Image(str(LOGO))
        img.drawHeight = 21 * mm
        img.drawWidth = 52 * mm
        logo_table = Table([[img]], colWidths=[doc.width])
        logo_table.setStyle(TableStyle([
            ("ALIGN", (0, 0), (-1, -1), "LEFT"),
            ("LEFTPADDING", (0, 0), (-1, -1), 0),
            ("RIGHTPADDING", (0, 0), (-1, -1), 0),
            ("TOPPADDING", (0, 0), (-1, -1), 0),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
        ]))
        story.append(logo_table)
        story.append(Spacer(1, 1.5 * mm))
    else:
        story.append(Paragraph("ΕΛΛΗΝΙΚΟ ΑΝΟΙΚΤΟ ΠΑΝΕΠΙΣΤΗΜΙΟ", header))
        story.append(Spacer(1, 4 * mm))

    story.append(Paragraph("ΠΡΟΓΡΑΜΜΑ ΣΠΟΥΔΩΝ «ΠΛΗΡΟΦΟΡΙΚΗ»", header))
    story.append(Paragraph("ΘΕ ΠΛΗ40 «Πρακτική Εξάσκηση σε θέματα Λογισμικού»", header))
    story.append(Spacer(1, 8 * mm))
    story.append(Paragraph("ΑΞΙΟΛΟΓΗΣΗ ΠΤΥΧΙΑΚΗΣ ΕΡΓΑΣΙΑΣ", title_style))
    story.append(Spacer(1, 2 * mm))

    rows = [
        ("Ονοματεπώνυμο φοιτητή:", student),
        ("Τίτλος πτυχιακής εργασίας:", thesis_title),
        ("Κωδικός πτυχιακής εργασίας:", code),
        ("Κρίση (1η/2η/3η ):", "1η"),
        ("Ημερομηνία αξιολόγησης πτυχιακής εργασίας:", "10/6/2026"),
        ("Ονοματεπώνυμο Κριτή (Μέλος):", "Παπαϊωάννου Βάιος"),
    ]
    data = []
    for label, value in rows:
        data.append([
            Paragraph(label, body_bold),
            Paragraph(value, body),
        ])
    fields = Table(data, colWidths=[70 * mm, doc.width - 70 * mm], hAlign="LEFT")
    fields.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
        ("RIGHTPADDING", (0, 0), (-1, -1), 2),
        ("TOPPADDING", (0, 0), (-1, -1), 2.2),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3.4),
    ]))
    story.append(fields)
    story.append(Spacer(1, 4 * mm))

    approval = Table([
        [Paragraph("ΕΓΚΡΙΝΕΤΑΙ ΓΙΑ ΠΑΡΟΥΣΙΑΣΗ (ΝΑΙ/ΟΧΙ)", body_bold), Paragraph("ΝΑΙ", body_bold)]
    ], colWidths=[doc.width - 28 * mm, 28 * mm], rowHeights=[12 * mm])
    approval.setStyle(TableStyle([
        ("BOX", (0, 0), (-1, -1), 0.75, colors.black),
        ("INNERGRID", (0, 0), (-1, -1), 0.75, colors.black),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("ALIGN", (1, 0), (1, 0), "CENTER"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
    ]))
    story.append(approval)
    story.append(Spacer(1, 6 * mm))
    story.append(Paragraph("Σχόλια για την εργασία:", body_bold))
    story.append(Spacer(1, 2.5 * mm))
    story.append(Paragraph(comments, comment_style))
    story.append(Spacer(1, 9 * mm))
    story.append(Paragraph("Το Μέλος ΕΚ", right_bold))
    story.append(Spacer(1, 12 * mm))
    story.append(Paragraph("Παπαϊωάννου Βάιος", right_bold))

    doc.build(story)
    return path


petalas_comments = (
    "Η εργασία είναι ολοκληρωμένη και ώριμη για παρουσίαση, με λειτουργική υλοποίηση και πλήρη "
    "πειραματική αξιολόγηση. Η προτεινόμενη προσέγγιση συνδυάζει ουσιαστικά την επεξεργασία δεδομένων "
    "δικτυακής κίνησης, τη μοντελοποίηση σε γράφο γνώσης και την αξιοποίηση μεγάλου γλωσσικού μοντέλου. "
    "Απομένουν περιορισμένες διορθώσεις στη διατύπωση, στη δομή, στην ορολογία, στην αρίθμηση και στις "
    "λεζάντες/παραπομπές. Εγκρίνεται για παρουσίαση."
)

tsalikidis_comments = (
    "Η εργασία αποτελεί ώριμο, ολοκληρωμένο και τεκμηριωμένο παραδοτέο, με σαφή ερευνητικό στόχο, "
    "συνεκτική αρχιτεκτονική, πραγματική υλοποίηση και αξιολόγηση με μετρήσιμα αποτελέσματα. Ιδιαίτερα "
    "θετική είναι η σαφής διάκριση μεταξύ τεκμηριωμένης ανίχνευσης, υποψήφιων σημασιολογικών συσχετίσεων "
    "και των ορίων του συστήματος. Οι παρατηρήσεις που απομένουν είναι κυρίως διορθωτικού χαρακτήρα και "
    "αφορούν την αρίθμηση, τους καταλόγους εικόνων και πινάκων, ορισμένα τυπογραφικά ζητήματα και τη "
    "συνοπτική τεκμηρίωση των thresholds και prompts. Εγκρίνεται για παρουσίαση."
)

make_form(
    "PLH40-Petalas-Petros-Giannis-Praktiko-Krisis-Papaioannou-Vaios-Melos-PE467.pdf",
    "Πεταλάς Πέτρος-Γιάννης",
    "Αξιοποίηση δεδομένων TCP/IP Traffic σε Μορφή Γραφήματος Γνώσης για τη Βελτίωση της Κυβερνοασφάλειας",
    "ΠΕ467",
    petalas_comments,
)

make_form(
    "PLH40-Tsalikidis-Vasileios-Praktiko-Krisis-Papaioannou-Vaios-Melos-PE2025-512.pdf",
    "Τσαλικίδης Βασίλειος",
    "Ενίσχυση της Ανίχνεσης Κυβερνοαπειλών μέσω Γράφων Γνώσης και του MITRE ATT&CK Framework: Προσέγγιση στο Στάδιο Detect του NIST Cybersecurity Framework",
    "2025_ΠΕ512",
    tsalikidis_comments,
)

print("Generated PDFs:")
for p in sorted(OUT.glob("*.pdf")):
    print(p.name)
