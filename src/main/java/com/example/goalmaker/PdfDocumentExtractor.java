package com.example.goalmaker;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

import java.time.Instant;
import java.util.Calendar;

final class PdfDocumentExtractor {
    Extraction extract(byte[] bytes, int maxPages) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new IllegalStateException("encrypted PDFs are not supported");
            }
            int pageCount = document.getNumberOfPages();
            int pagesExtracted = Math.min(pageCount, Math.max(1, maxPages));
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pagesExtracted);
            String text = normalize(stripper.getText(document));
            PDDocumentInformation information = document.getDocumentInformation();
            return new Extraction(text, clean(information.getTitle()), clean(information.getAuthor()),
                    instant(information.getCreationDate()), instant(information.getModificationDate()),
                    pageCount, pagesExtracted, "pdfbox");
        }
    }

    private static String instant(Calendar value) {
        return value == null ? "" : Instant.ofEpochMilli(value.getTimeInMillis()).toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        return clean(value);
    }

    record Extraction(String text, String title, String author, String publishedAt,
                      String modifiedAt, int pageCount, int pagesExtracted, String method) {}
}
