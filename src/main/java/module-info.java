module org.example.filmotecadelreves {


    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.base;
    requires javafx.web;
    requires javafx.swing;
    requires javafx.media;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.almasb.fxgl.all;
    requires json.simple;
    requires jlibtorrent;

    opens org.example.filmotecadelreves to javafx.fxml;
    opens org.example.filmotecadelreves.ui to javafx.fxml;

    exports org.example.filmotecadelreves;
    exports org.example.filmotecadelreves.ui;
}