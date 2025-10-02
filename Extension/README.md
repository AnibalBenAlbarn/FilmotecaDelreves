# Extension directory

Coloque los archivos `StreamtapeDownloader.crx` y `PopUpStrictOld.crx` dentro de esta carpeta cuando ejecute la aplicación en Windows. El gestor de streams lo buscará tanto en esta ubicación relativa como en las rutas absolutas indicadas en la documentación (`C:\Users\Anibal\IdeaProjects\FilmotecaDelreves\Extension\StreamtapeDownloader.crx` y `C:\Users\Anibal\IdeaProjects\FilmotecaDelreves\Extension\PopUpStrictOld.crx`).

Si dispone de la extensión descomprimida (por ejemplo, tras clonar el repositorio de la extensión), cree una carpeta en esta misma ubicación y copie su contenido allí. Por defecto la aplicación buscará un directorio `streamtape-extension-master/` o `StreamtapeDownloader/` y, si existe, cargará la extensión en modo **unpacked**, evitando los problemas de compatibilidad de los archivos `.crx` en versiones recientes de Chrome.
