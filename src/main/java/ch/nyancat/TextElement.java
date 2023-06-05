package ch.nyancat;

public class TextElement {
    private String contents;
    private float height;
    private float left;
    private float top;
    private float width;

    public TextElement(String contents, float height, float left, float top, float width) {
        this.contents = contents;
        this.height = height;
        this.left = left;
        this.top = top;
        this.width = width;
    }

    public String getContents() {
        return contents;
    }

    public float getHeight() {
        return height;
    }

    public float getLeft() {
        return left;
    }

    public float getTop() {
        return top;
    }

    public void setWidth(float width) {
        this.width = width;
    }
}
