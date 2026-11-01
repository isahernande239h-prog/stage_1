package es.ulpgc.bigdata.stage1;

import java.io.IOException;

public class DownloadException extends IOException {
    public DownloadException(String message) {
        super(message);
    }
}
