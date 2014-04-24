package de.is24.deadcode4j;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static java.io.File.createTempFile;

@SuppressWarnings("ConstantConditions")
public class A_Repository {

    @Test(expected = NullPointerException.class)
    public void throwsAnExceptionIfTheRepositoryIsNull() throws IOException {
        new Repository(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsAnExceptionIfTheRepositoryIsNoDirectory() throws IOException {
        File tmpFile = createTempFile("JUnit", ".tmp");
        tmpFile.deleteOnExit();
        new Repository(tmpFile);
    }

}
