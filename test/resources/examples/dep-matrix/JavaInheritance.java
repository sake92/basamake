package demo;

import org.apache.commons.net.ftp.FTPClient;
import java.util.ArrayList;
import java.util.List;

public class JavaInheritance extends FTPClient implements List<String> { // (a) extends dep type, (b) implements JDK interface
    private final ArrayList<String> items = new ArrayList<>();           // (c) JDK field type
}
