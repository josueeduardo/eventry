package io.joshworks.fstore.log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Utils {

    //terrible work around for waiting the mapped buffer to release file lock
    public static void tryDelete(File file) {
        int maxTries = 5;
        int counter = 0;
        while (counter++ < maxTries) {
            try {
                if (file.isDirectory()) {
                    String[] list = file.list();
                    if (list != null)
                        for (String f : list) {
                            Path path = new File(file, f).toPath();
                            System.out.println("Deleting " + path);
                            if (!Files.deleteIfExists(path)) {
                                throw new RuntimeException("Failed to delete file");
                            }
                        }
                }
                if (!Files.deleteIfExists(file.toPath())) {
                    throw new RuntimeException("Failed to delete file");
                }
                break;
            } catch (Exception e) {
                System.err.println(":: LOCK NOT RELEASED YET ::");
                e.printStackTrace();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    public static void removeFiles(File directory) throws IOException {
        String[] files = directory.list();
        if (files != null) {
            for (String s : files) {
                System.out.println("Deleting " + s);
                Files.delete(new File(directory, s).toPath());
            }
        }
        Files.delete(directory.toPath());
    }

//    private static void deleteDirectory(File dir) throws IOException {
//        Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<>() {
//
//            @Override
//            public FileVisitResult visitFile(Path file,
//                                             BasicFileAttributes attrs) throws IOException {
//
//                System.out.println("Deleting file: " + file);
//                Files.delete(file);
//                return CONTINUE;
//            }
//
//            @Override
//            public FileVisitResult postVisitDirectory(Path dir,
//                                                      IOException exc) throws IOException {
//
//                System.out.println("Deleting dir: " + dir);
//                if (exc == null) {
//                    Files.delete(dir);
//                    return CONTINUE;
//                } else {
//                    throw exc;
//                }
//            }
//
//        });
//    }
}