package ch.nyancat;

import com.github.kevinsawicki.http.HttpRequest;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfTemplate;
import com.itextpdf.text.pdf.PdfWriter;
import org.apache.commons.cli.*;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;

public class Main {
    public static void main(String[] args) {
        Options options = new Options();
        Option url = new Option("u", "url", true, "The base URL of the book (https://[...].prod.aws.cornelsen.de/i/d/[...]/h/[...]/)");
        url.setRequired(true);
        options.addOption(url);
        Option pspdfkitToken = new Option("pt", "pspdfkittoken", true, "The X-PSPDFKit-Token HTTP header");
        pspdfkitToken.setRequired(true);
        options.addOption(pspdfkitToken);
        Option imageToken = new Option("it", "imagetoken", true, "The X-PSPDFKit-Image-Token HTTP header");
        imageToken.setRequired(true);
        options.addOption(imageToken);
        Option versionHeader = new Option("v", "versionheader", true, "The PSPDFKit-Version HTTP header");
        versionHeader.setRequired(true);
        options.addOption(versionHeader);
        Option savePath = new Option("o", "output", true, "Output path of the pdf");
        options.addOption(savePath);
        Option resume = new Option("r", "resume", false, "Tries to resume the download");
        options.addOption(resume);
        Option temps = new Option("t", "temps", false, "Keep the temporary files (png files of each page)");
        options.addOption(temps);
        Option qualityMultiplicator = new Option("qm", "qualitymultiplicator", true, "The multiplicator to increase the image quality. The 1x resolution is 538px*737px and the default multiplicator is 2x");
        options.addOption(qualityMultiplicator);
        Option textEnabledOption = new Option("te", "textenabled", false, "Use this if you want to copy text out of the pdf file");
        options.addOption(textEnabledOption);

        HelpFormatter helper = new HelpFormatter();
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = null;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            helper.printHelp("Cornelsen2Pdf.jar [-u url] [-pt pspdfkittoken] [-it imagetoken] [-v versionheader] [-o output] [-r resume] [-t temps] [-qm qualitymultiplicator] [-te textenabled]", options);
            System.exit(0);
        }
        String baseURL = cmd.getOptionValue("u");
        if (!baseURL.endsWith("/")) {
            baseURL += "/";
        }
        String pspdfkittokenString = cmd.getOptionValue("pt");
        String imageTokenString = cmd.getOptionValue("it");
        int qm = 2;
        if (cmd.hasOption("qm")) {
            qm = Integer.parseInt(cmd.getOptionValue("qm"));
        }
        String output;
        if (cmd.hasOption("o")) {
            output = cmd.getOptionValue("o");
        } else {
            // use current working directory as output path
            output = System.getProperty("user.dir");
        }
        if (!output.endsWith("/")) {
            output += "/";
        }
        int pages = getSitesNumber(baseURL, pspdfkittokenString, cmd.getOptionValue("v"));
        int offset = 0;
        if (cmd.hasOption("r")) {
            System.out.println("Trying to resume download...");
            offset = getDownloadedPages(output, pages);
        }
        downloadImages(offset, pages, output, imageTokenString, baseURL, qm);
        System.out.println("Downloading text...");
        downloadText(offset, pages, output, pspdfkittokenString, baseURL);
        System.out.println("Download finished. Merging images into one pdf...");
        imagesToPdf(output, pages, "output", qm, cmd.hasOption("te"));
        if (!cmd.hasOption("t")) {
            System.out.println("Cleaning up...");
            cleanup(output, pages);
        }
        System.out.println("Done!");
    }

    public static void downloadText(int offset, int pagesToDownload, String output, String pspdfkitToken, String baseUrl) {
        for (int currentPage = offset; currentPage < pagesToDownload; currentPage++) {
            File file = new File(output + "page" + currentPage + "text.json");
            String jsonString = HttpRequest.get(baseUrl + "page-" + currentPage + "-text").header("X-PSPDFKit-Token", pspdfkitToken).acceptJson().body();
            FileOutputStream s;
            try {
                s = new FileOutputStream(file);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            try {
                s.write(jsonString.getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void downloadImages(int offset, int pagesToDownload, String output, String imageTokenString, String baseURL, int qm) {
        for (int currentPage = offset; currentPage < pagesToDownload; currentPage++) {
            System.out.println("Downloading page " + currentPage + " of " + pagesToDownload + ", " + (pagesToDownload - currentPage - 1) + " pages left");
            String requestUrl = baseURL +
                    "page-" +
                    currentPage +
                    "-dimensions-" +
                    538 * qm +
                    "-" +
                    737 * qm +
                    "-tile-0-0-" +
                    538 * qm +
                    "-" +
                    737 * qm;
            try {
                InputStream stream = HttpRequest.get(requestUrl).accept("image/webp,*/*").header("X-PSPDFKit-Image-Token", imageTokenString).stream();
                File file = new File(output + "page" + currentPage + ".webp");
                FileOutputStream s = new FileOutputStream(file);
                s.write(stream.readAllBytes());
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
            // convert webp to png
            convertWebpToPng(output, "page" + currentPage + ".webp", currentPage);
            System.out.println("Success!");
        }
    }

    public static void imagesToPdf(String path, int sites, String fileName, int qm, boolean withText) {
        File file0 = new File(path + "page0.png");
        com.itextpdf.text.Image image0;
        try {
            image0 = com.itextpdf.text.Image.getInstance(file0.toURI().toURL());
        } catch (BadElementException | IOException e) {
            throw new RuntimeException(e);
        }
        com.itextpdf.text.Rectangle r0 = new com.itextpdf.text.Rectangle(image0.getPlainWidth(), image0.getPlainHeight());
        Document document = new Document(r0, 0, 0, 0, 0);
        try {
            PdfWriter w = PdfWriter.getInstance(document, new FileOutputStream(path + fileName + ".pdf"));
            w.setStrictImageSequence(true);
            document.open();
            for (int i = 0; i < sites; i++) {
                File file = new File(path + "page" + i + ".png");
                Image img = com.itextpdf.text.Image.getInstance(file.toURI().toURL());
                if (withText) {
                    PdfContentByte cb = w.getDirectContentUnder();
                    TextElement[] textElements = getText(path, i);
                    float iwidth = img.getScaledWidth();
                    float iheight = img.getScaledHeight();
                    PdfTemplate template = cb.createTemplate(iwidth, iheight);
                    for (int j = 0; j < textElements.length - 1; j++) {
                        ColumnText.showTextAligned(template, Element.ALIGN_LEFT, new Phrase(textElements[j].getContents(), new Font(Font.FontFamily.HELVETICA, (int) (textElements[j].getHeight() - 3) * qm, Font.BOLD, BaseColor.BLACK)), (textElements[j].getLeft()) * qm, (737 - textElements[j].getTop() - 7) * qm, 0);
                    }
                    template.addImage(img, iwidth, 0, 0, iheight, 0, 0);

                    img = Image.getInstance(template);
                }
                document.add(img);
                document.newPage();
            }
            document.close();
        } catch (DocumentException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static TextElement[] getText(String path, int page) {
        TextElement[] textElements;
        File f = new File(path + "page" + page + "text.json");
        try {
            String jsonString = Files.readString(f.toPath());
            JSONObject rootObj = new JSONObject(jsonString);
            JSONArray rootArray = rootObj.getJSONArray("textLines");
            textElements = new TextElement[rootArray.length()];
            for (int i = 0; i < rootArray.length(); i++) {
                String contents = new JSONObject(rootArray.get(i).toString()).getString("contents");
                float height = new JSONObject(rootArray.get(i).toString()).getFloat("height");
                float left = new JSONObject(rootArray.get(i).toString()).getFloat("left");
                float top = new JSONObject(rootArray.get(i).toString()).getFloat("top");
                float width = new JSONObject(rootArray.get(i).toString()).getFloat("width");
                textElements[i] = new TextElement(contents, height, left, top, width);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return textElements;
    }

    public static void convertWebpToPng(String pathToWebp, String fileName, int currentPage) {
        try {
            BufferedImage image = ImageIO.read(new File(pathToWebp + fileName));
            ImageIO.write(image, "png", new File(pathToWebp + "page" + currentPage + ".png"));
            new File(pathToWebp + fileName).delete();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static int getDownloadedPages(String path, int expectedSitesNumber) {
        for (int i = 0; i < expectedSitesNumber; i++) {
            File page = new File(path + "page" + i + ".png");
            if (!page.isFile() || !(page.length() > 0)) {
                return i;
            }
        }
        return expectedSitesNumber;
    }

    public static void cleanup(String path, int sitesNumber) {
        for (int i = 0; i < sitesNumber; i++) {
            new File(path + "page" + i + ".png").delete();
            new File(path + "page" + i + ".webp").delete();
            new File(path + "page" + i + "text.json").delete();
        }
    }

    public static int getSitesNumber(String baseURL, String pspdfkitTokenString, String versionHeader) {
        String dataURL = baseURL + "document.json";
        HttpRequest r = HttpRequest.get(dataURL).header("X-PSPDFKit-Token", pspdfkitTokenString).header("PSPDFKit-Platform", "web").header("PSPDFKit-Version", versionHeader);
        if (!r.ok()) {
            System.out.println("Error while getting the number of pages. Aborting");
            System.exit(0);
        }
        JSONObject bookData = new JSONObject(r.body());
        return bookData.getJSONObject("data").getInt("pageCount");
    }
}