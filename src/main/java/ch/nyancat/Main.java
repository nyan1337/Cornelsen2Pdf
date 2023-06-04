package ch.nyancat;

import com.github.kevinsawicki.http.HttpRequest;
import com.itextpdf.text.BadElementException;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfWriter;
import org.apache.commons.cli.*;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

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

        HelpFormatter helper = new HelpFormatter();
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = null;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            helper.printHelp("Cornelsen2Pdf.jar [-u url] [-pt pspdfkittoken] [-it imagetoken] [-v versionheader] [-o output] [-r resume] [-t temps] [-qm qualitymultiplicator]", options);
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
            offset = getDownloadedSites(output, pages);
        }
        downloadImages(offset, pages, output, imageTokenString, baseURL, qm);
        System.out.println("Download finished. Merging images into one pdf...");
        imagesToPdf(output, pages, "output");
        if (!cmd.hasOption("t")) {
            System.out.println("Cleaning up...");
            cleanup(output, pages);
        }
        System.out.println("Done!");
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
            InputStream stream = HttpRequest.get(requestUrl).header("X-PSPDFKit-Image-Token", imageTokenString).stream();
            File file = new File(output + "page" + currentPage + ".webp");
            try {
                ImageIO.write(ImageIO.read(stream), "webp", file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            // convert webp to png
            convertWebpToPng(output, "page" + currentPage + ".webp", currentPage);
            System.out.println("Success!");
        }
    }

    public static void imagesToPdf(String path, int sites, String fileName) {
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
            PdfWriter.getInstance(document, new FileOutputStream(path + fileName + ".pdf"));
            document.open();
            for (int i = 0; i < sites; i++) {
                File file = new File(path + "page" + i + ".png");
                com.itextpdf.text.Image image = com.itextpdf.text.Image.getInstance(file.toURI().toURL());
                com.itextpdf.text.Rectangle r = new Rectangle(image.getPlainWidth(), image.getPlainHeight());
                document.setPageSize(r);
                document.add(image);
                document.newPage();
            }
            document.close();
        } catch (DocumentException | IOException e) {
            throw new RuntimeException(e);
        }
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

    public static int getDownloadedSites(String path, int expectedSitesNumber) {
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