module org.example.filmotecadelreves {


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
    exports org.example.filmotecadelreves;
}