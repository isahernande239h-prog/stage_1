package es.ulpgc.bigdata.stage1;

import java.io.IOException;

public class BookNotFoundException extends IOException {
    public BookNotFoundException(int bookId) {
        super(String.valueOf(bookId));
    }
}
