package org.refinery_platform.owl2neo4j;

/** OWL API */
import org.json.JSONTokener;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.util.AutoIRIMapper;
import org.semanticweb.owlapi.util.OWLClassExpressionVisitorAdapter;

/** Reasoner */
import org.semanticweb.HermiT.Reasoner;

/** Apache commons */
import org.apache.commons.cli.*;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;

/** Jersey RESTful client */
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.Headers;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.HttpResponse;

/** JSON **/
import org.json.JSONObject;
import org.json.JSONArray;
import javax.json.Json;
import javax.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.io.FileReader;

import org.apache.commons.io.FilenameUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Owl2Neo4J {

    private static String REST_ENDPOINT = "/db/data";
    private static String TRANSACTION_ENDPOINT = "/db/data/transaction";

    public static String ROOT_ONTOLOGY = "OWL";
    public static String ROOT_CLASS = "Thing";
    public static String ROOT_CLASS_ONT_ID = ROOT_ONTOLOGY + ":" + ROOT_CLASS;
    public static String ROOT_CLASS_URI = "http://www.w3.org/2002/07/owl#" + ROOT_CLASS;

    // Graph related nodes
    private static String CLASS_NODE_LABEL = "Class";
    private static String INDIVIDUAL_NODE_LABEL = "Individual";
    // Meta data related nodes
    private static String ONTOLOGY_NODE_LABEL = "Ontology";
    private static String RELATIONSHIP_NODE_LABEL = "Relationship";
    private static String PROPERTY_NODE_LABEL = "Property";

    private String path_to_batch;
    private String path_to_owl;
    private String ontology_name;
    private String ontology_acronym;
    private String server_root_url;
    private String neo4j_authentication_header;
    private String transaction;
    private Set<String> eqps = new HashSet<>();  // Existential quantification property strings
    private Set<OWLObjectPropertyExpression> eqp = new HashSet<>();  // Existential quantification properties
    private Boolean include_import_closure = false;
    private Boolean no_local_owl_files = false;

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private IRI documentIRI;
    private OWLDataFactory dataFactory;
    private String ontUri;
    private String versionIri;

    private Logger cqlLogger;
    private FileHandler fh;
    private Boolean verbose_output = false;

    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_DIM = "\u001B[2m";
    public static final String ANSI_RESET_DIM = "\u001B[22m";

    public static final String VERSION = "0.6.0";

    public static List<String> fileList (String directory, String fileExt) {
        List<String> fileNames = new ArrayList<>();
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(Paths.get(directory))) {
            for (Path path : directoryStream) {
                if (FilenameUtils.isExtension(path.getFileName().toString(), fileExt)) {
                    fileNames.add(path.toString());
                }
            }
        } catch (IOException ex) {}
        return fileNames;
    }

    // Inline class handling labels
    public class Label {
        private String text;
        private String lang;

        public Label (String text, String lang) {
            this.text = text;
            this.lang = lang;
        }

        public String getLang () {
            return this.lang;
        }

        public String getText () {
            return this.text;
        }

        public void setLang (String lang) {
            this.lang = lang;
        }

        public void setText (String text) {
            this.text = text;
        }
    }

    /**
     * Tiny class for storing pairs
     * @param <X>
     * @param <Y>
     */
    public static class Tuple<X, Y> {
        public final X x;
        public final Y y;
        public Tuple(X x, Y y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ")";
        }
    }

    /**
     * Visits existential restrictions and collects the properties which are
     * restricted.
     */
    private static class RestrictionVisitor extends OWLClassExpressionVisitorAdapter {

        private final Set<Tuple> restrictions;

        public RestrictionVisitor() {
            restrictions = new HashSet<>();
        }

        public Set<Tuple> getRestrictions () {
            return restrictions;
        }

        @Override
        public void visit(OWLObjectSomeValuesFrom clazz) {
            // This method gets called when a class expression is an existential
            // (someValuesFrom) restriction and it asks us to visit it
            if (! clazz.getFiller().isAnonymous()) {
                restrictions.add(new Tuple(clazz.getProperty(), clazz.getFiller().asOWLClass()));
            }
        }
    }

    public static void main(String[] args) {
        Owl2Neo4J ontParser = new Owl2Neo4J(args);

        long startImport = System.nanoTime();
        long endImport = System.nanoTime();

        if (StringUtils.isBlank(ontParser.path_to_batch)) {
            ontParser.checkServer();
            ontParser.importOntologies();
        } else {
            JSONArray ontologies = new JSONArray();
            JSONObject jsonObject = new JSONObject();
            String basePath = FilenameUtils.getFullPath(ontParser.path_to_batch);

            // Read JSON file
            try {
                FileReader reader = new FileReader(ontParser.path_to_batch);

                JSONTokener jsonTokener = new JSONTokener(reader);
                jsonObject = new JSONObject(jsonTokener);

                ontologies = jsonObject.getJSONArray("ontologies");

            } catch (Exception e) {
                print_error("Error loading JSON file.");
                print_error(e.getMessage());
                System.exit(1);
            }

            // Read global properties
            try {
                if (jsonObject.has("server")) {
                    ontParser.server_root_url = jsonObject.getString("server");
                }
            } catch (Exception e) {
                print_error("Error extracting global settings.");
            }

            ontParser.checkServer();

            // Loop over ontologies to be imported and import them
            try {
                for (int i = 0; i < ontologies.length(); i++) {
                    ontParser.path_to_owl = new File(basePath, ontologies.getJSONObject(i).getString("o")).getPath();
                    ontParser.ontology_name = ontologies.getJSONObject(i).getString("n");
                    ontParser.ontology_acronym = ontologies.getJSONObject(i).getString("a").toUpperCase();
                    if (ontologies.getJSONObject(i).has("i")) {
                        ontParser.include_import_closure = ontologies.getJSONObject(i).getBoolean("i");
                    } else {
                        ontParser.include_import_closure = false;
                    }
                    ontParser.importOntologies();
                }
            }  catch (Exception e) {
                print_error("Error during batch import");
                print_error(e.getMessage());
                System.exit(1);
            }
        }

        long totalImportSec = TimeUnit.NANOSECONDS.toMinutes(endImport - startImport);
        long totalImportMin = TimeUnit.NANOSECONDS.toSeconds(endImport - startImport);

        // Unirest has be closed explicitly
        try {
            Unirest.shutdown();
        } catch (Exception e) {
            print_error("Error shutting down Unirest");
            print_error(e.getMessage());
            System.exit(1);
        }

        // Print some performance related numbers
        if (ontParser.verbose_output) {
            System.out.println("---");
            System.out.println(
                "Total import time:   " +
                    totalImportMin +
                    " min and" +
                    totalImportSec +
                    " sec"
            );
        }
    }

    public Owl2Neo4J(String[] args) {
        parseCommandLineArguments(args);
    }

    public void checkServer() {
        if (StringUtils.isBlank(this.server_root_url)) {
            this.server_root_url = "http://localhost:7474";
        }

        Unirest.setDefaultHeader("Content-type", "application/json");
        Unirest.setDefaultHeader("Accept", "application/json; charset=UTF-8");
        // Yields better performance and reduces memory load on the Neo4J server
        // http://neo4j.com/docs/stable/rest-api-streaming.html
        Unirest.setDefaultHeader("X-Stream", "true");
        Unirest.setDefaultHeader(
            "Authorization", this.neo4j_authentication_header
        );

        // Test if server is available
        try {
            if (this.verbose_output) {
                System.out.println("Checking Neo4J at " + this.server_root_url + "... " + ANSI_DIM);
            } else {
                System.out.print("Checking Neo4J at " + this.server_root_url + "... ");
            }

            Unirest.get(
                this.server_root_url
            ).asJson();

            if (this.verbose_output) {
                System.out.println(ANSI_RESET + "Checking Neo4J at " + this.server_root_url + "... " + ANSI_GREEN + "\u2713" + ANSI_RESET);
            } else {
                System.out.println(ANSI_GREEN + "\u2713" + ANSI_RESET);
            }
        } catch (Exception e) {
            print_error("Error querying Neo4J server root URL");
            print_error(e.getMessage());
            System.exit(1);
        }

        // Try authentication
        try {
            if (this.verbose_output) {
                System.out.println("Checking credentials for Neo4J... " + ANSI_DIM);
            } else {
                System.out.print("Checking credentials for Neo4J... ");
            }

            Unirest.get(
                this.server_root_url + REST_ENDPOINT
            ).asJson();

            if (this.verbose_output) {
                System.out.println(ANSI_RESET + "Checking credentials for Neo4J... " + ANSI_GREEN + "\u2713" + ANSI_RESET);
            } else {
                System.out.println(ANSI_GREEN + "\u2713" + ANSI_RESET);
            }
        } catch (Exception e) {
            print_error("Error querying Neo4J REST endpoint");
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    public void importOntologies() {
        long loadTimeSec;
        long loadTimeMin;

        try {
            if (this.verbose_output) {
                System.out.println("Loading " + this.ontology_acronym + "... " + ANSI_DIM);
            } else {
                System.out.print("Loading " + this.ontology_acronym + "... ");
            }

            long start = System.nanoTime();
            this.loadOntology();
            long end = System.nanoTime();
            loadTimeMin = TimeUnit.NANOSECONDS.toMinutes(end - start);
            loadTimeSec = TimeUnit.NANOSECONDS.toSeconds(end - start) - (60 * loadTimeMin);

            if (this.verbose_output) {
                System.out.println(ANSI_RESET + "Loading " + this.ontology_acronym + "... " + ANSI_GREEN + "\u2713" + ANSI_RESET);
            } else {
                System.out.println(
                    ANSI_GREEN + "\u2713 " + ANSI_RESET +
                        ANSI_DIM + "  ("  + loadTimeMin + " min and " + loadTimeSec + " sec)" + ANSI_RESET_DIM
                );
            }
        } catch (Exception e) {
            print_error("Error loading the ontology");
            print_error(e.getMessage());
            System.exit(1);
        }

        long importTimeSec;
        long importTimeMin;
        try {
            if (this.verbose_output) {
                System.out.println("Importing " + this.ontology_acronym + "... " + ANSI_DIM);
            } else {
                System.out.print("Importing " + this.ontology_acronym + "... ");
            }

            long start = System.nanoTime();
            this.importOntology();
            long end = System.nanoTime();
            importTimeMin = TimeUnit.NANOSECONDS.toMinutes(end - start);
            importTimeSec = TimeUnit.NANOSECONDS.toSeconds(end - start) - (60 * importTimeMin);

            if (this.verbose_output) {
                System.out.println(ANSI_RESET + "Importing " + this.ontology_acronym + "... " + ANSI_GREEN + "\u2713" + ANSI_RESET);
            } else {
                System.out.println(
                    ANSI_GREEN + "\u2713" + ANSI_RESET +
                        ANSI_DIM + "  (" + importTimeMin + " min and " + importTimeSec + " sec)" + ANSI_RESET_DIM
                );
            }
        } catch (Exception e) {
            print_error("Error importing the ontology");
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    public void loadOntology() throws Exception {
        this.manager = OWLManager.createOWLOntologyManager();

        Path ontFilePath = Paths.get(this.path_to_owl);
        if (Files.notExists(ontFilePath)) {
            throw new Exception("The option `-o` doesn't point to a file.");
        }

        if (!this.no_local_owl_files) {
            this.manager.addIRIMapper(
                new AutoIRIMapper(
                    ontFilePath.getParent().toFile(), true
                )
            );
        }

        this.documentIRI = IRI.create("file:" + this.path_to_owl);
        this.ontology = this.manager.loadOntologyFromOntologyDocument(documentIRI);

        this.dataFactory = OWLManager.getOWLDataFactory();

        try {
            this.ontUri = this.ontology.getOntologyID().getOntologyIRI().toString();
        } catch (NullPointerException e) {
            throw new Exception("Ontology doesn't have a URI.");
        }

        try {
            this.versionIri = this.ontology.getOntologyID().getVersionIRI().toString();
        } catch (NullPointerException e) {
            this.versionIri = null;
        }

        if (this.verbose_output) {
            System.out.println("Document IRI: " + documentIRI);
            System.out.println("Ontology IRI: " + this.ontUri);
            System.out.println("Version  IRI: " + this.versionIri);
        }
    }

    private void importOntology() throws Exception
    {
        OWLReasonerFactory reasonerFactory = new Reasoner.ReasonerFactory();
        OWLReasonerConfiguration config;
        if (this.verbose_output) {
            ConsoleProgressMonitor progressMonitor = new ConsoleProgressMonitor();
            config = new SimpleConfiguration(
                progressMonitor
            );
        } else {
            config = new SimpleConfiguration();
        }
        OWLReasoner reasoner = reasonerFactory.createReasoner(this.ontology, config);
        reasoner.precomputeInferences();

        // Init Cypher logger
        this.cqlLogger = Logger.getLogger("Cypher:" + this.ontology_acronym);
        if (this.verbose_output) {
            try {
                // Create at most five 10MB log files.
                this.fh = new FileHandler("Cypher log for " + this.ontology_acronym + ".log", 10485760, 5);
                this.cqlLogger.addHandler(fh);
                SimpleFormatter formatter = new SimpleFormatter();
                fh.setFormatter(formatter);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // This part was inspired by:
        // http://neo4j.com/blog/and-now-for-something-completely-different-using-owl-with-neo4j/
        try {
            initTransaction();

            // Create a node for the ontology
            createNode(
                ONTOLOGY_NODE_LABEL,
                this.ontology_name,
                this.ontUri
            );

            setProperty(
                ONTOLOGY_NODE_LABEL,
                this.ontUri,
                "acronym",
                this.ontology_acronym
            );

            setProperty(
                ONTOLOGY_NODE_LABEL,
                this.ontUri,
                "uri",
                this.ontUri
            );

            if (this.versionIri != null) {
                setProperty(
                    ONTOLOGY_NODE_LABEL,
                    this.ontUri,
                    "version",
                    this.versionIri
                );
            }

            // Create root node "owl:Thing"
            createNode(
                CLASS_NODE_LABEL,
                ROOT_CLASS_ONT_ID,
                ROOT_CLASS_URI
            );

            if (!this.eqps.isEmpty()) {
                for (String property: this.eqps) {
                    this.eqp.add(this.dataFactory.getOWLObjectProperty(IRI.create(property)));
                }
            }

            for (OWLClass c: this.ontology.getClassesInSignature(this.include_import_closure)) {
                // Skip unsatisfiable classes like `owl:Nothing`.
                if (!reasoner.isSatisfiable(c)) {
                    continue;
                }

                String classString = c.toString();
                String classUri = this.extractUri(classString);
                String classOntID = this.getOntID(classUri);

                String superClassString;
                String superClassUri;
                String superClassOntID;

                createNode(CLASS_NODE_LABEL, classOntID, classUri);

                this.storeLabel(c, classUri);

                // A node set is a set of nodes.
                NodeSet<OWLClass> superClassNodeSet = reasoner.getSuperClasses(c, true);

                if (superClassNodeSet.isEmpty()) {
                    createRelationship(
                        CLASS_NODE_LABEL,
                        classUri,
                        CLASS_NODE_LABEL,
                        ROOT_CLASS_URI,
                        "RDFS:subClassOf"
                    );
                } else {
                    // A node is a set of equivalent OWLClasses.
                    // http://owlapi.sourceforge.net/javadoc/org/semanticweb/owlapi/reasoner/Node.html
                    for (Node<OWLClass> superClassNode: superClassNodeSet) {
                        if (superClassNode.isTopNode()) {
                            // The top node represents owl:Thing and OWL classes equivalent to it.
                            createRelationship(
                                CLASS_NODE_LABEL,
                                classUri,
                                CLASS_NODE_LABEL,
                                ROOT_CLASS_URI,
                                "RDFS:subClassOf"
                            );
                        } else {
                            // We iterate over all superclasses except unsatisfiable classes, e.g. owl:Nothing and other
                            // classes equivalent to it.
                            for (OWLClass superClass: superClassNode.getEntitiesMinusBottom()) {
                                superClassString = superClass.toString();
                                superClassUri = this.extractUri(superClassString);
                                superClassOntID = this.getOntID(superClassUri);

                                createNode(
                                    CLASS_NODE_LABEL,
                                    superClassOntID,
                                    superClassUri
                                );

                                createRelationship(
                                    CLASS_NODE_LABEL,
                                    classUri,
                                    CLASS_NODE_LABEL,
                                    superClassUri,
                                    "RDFS:subClassOf"
                                );
                            }
                        }
                    }
                }

                if (!this.eqp.isEmpty()) {
                    // Create a visitor for extracting existential restrictions they can be seen as some sort of class
                    // property.
                    // http://www.w3.org/TR/2004/REC-owl-guide-20040210/#PropertyRestrictions
                    RestrictionVisitor restrictionVisitor = new RestrictionVisitor();

                    // Get all subclass axioms for the current class
                    for (OWLSubClassOfAxiom axiom: this.ontology.getSubClassAxiomsForSubClass(c)) {
                        // Get all superclasses based on the axiom, which includes superclasses based on existential
                        // restrictions.
                        OWLClassExpression superClass = axiom.getSuperClass();
                        // Ask our superclass to accept a visit from the RestrictionVisitor
                        superClass.accept(restrictionVisitor);
                    }

                    for (Tuple restriction: restrictionVisitor.getRestrictions()) {
                        if (this.eqp.contains(restriction.x)) {
                            superClassString = restriction.y.toString();
                            superClassUri = this.extractUri(superClassString);
                            superClassOntID = this.getOntID(superClassUri);

                            createNode(
                                CLASS_NODE_LABEL,
                                superClassOntID,
                                superClassUri
                            );

                            createRelationship(
                                CLASS_NODE_LABEL,
                                classUri,
                                CLASS_NODE_LABEL,
                                superClassUri,
                                this.getOntID(this.extractUri(restriction.x.toString()))
                            );
                        }
                    }
                }

                Set<OWLClass> equivalentClasses = getEquivalentClasses(reasoner, c);

                for (OWLClass ec : equivalentClasses) {
                    String ecString = ec.toString();
                    String ecUri = this.extractUri(ecString);
                    String ecOntID = this.getOntID(ecUri);

                    if (!ecUri.equals(classUri)) {
                        createNode(
                            CLASS_NODE_LABEL,
                            ecOntID,
                            ecUri
                        );

                        createRelationship(
                            CLASS_NODE_LABEL,
                            ecUri,
                            CLASS_NODE_LABEL,
                            classUri,
                            "OWL:equivalentClass"
                        );
                    }
                }
            }
            commitTransaction();
        } catch (Exception e) {
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    public String extractUri (String classString) {
        String classUri = classString;
        int openingAngleBracketPos = classString.indexOf("<");
        int closingAngleBracketPos = classString.lastIndexOf(">");
        try {
            if (openingAngleBracketPos >= 0 && closingAngleBracketPos >= 0) {
                classUri = classString.substring(
                    classString.indexOf("<") + 1,
                    classString.lastIndexOf(">")
                );
            }
        } catch (Exception e) {
            print_error("Couldn't extract URI of '" + classString + "'");
            print_error(e.getMessage());
            System.exit(1);
        }
        return classUri;
    }

    public String getOntID (String classUri) {
        String idSpace = "";
        String classOntID = classUri;
        // First extract the substring after the last slash to avoid possible
        // conflicts
        if (classOntID.contains("/")) {
            int lastSlash = classOntID.lastIndexOf("/");
            if (lastSlash >= 0) {
                String tmp = classOntID.substring(lastSlash);
                if (tmp.length() == 1) {
                    tmp = classOntID.substring(0, lastSlash);
                    lastSlash = tmp.lastIndexOf("/");
                    if (lastSlash >= 0) {
                        tmp = tmp.substring(lastSlash);
                    }
                }
                if (tmp.length() > 1) {
                    classOntID = tmp.substring(1);
                }
            }
        }
        // OWL IDs start with `#` so we extract everything after that.
        int hashPos = classOntID.indexOf("#");
        if (hashPos >= 0 && hashPos + 1 != classOntID.length()) {
            classOntID = classOntID.substring(
                hashPos + 1
            );
            if (this.ontUri.equals(classUri.substring(0, classUri.indexOf("#")))) {
                idSpace = this.ontology_acronym;
            }
        }
        // If the string contains an underscore than it is most likely an OBO ontology converted to OWL. The prefix is
        // different in this case. We will use the ID space of OBO.
        // For more details: http://www.obofoundry.org/id-policy.shtml
        int underscorePos = classOntID.indexOf("_");
        if (underscorePos >= 0 && underscorePos + 1 != classOntID.length()) {
            if (idSpace.length() == 0) {
                idSpace = classOntID.substring(
                    0,
                    underscorePos
                );
            }
            classOntID = classOntID.substring(underscorePos + 1);
        }
        if (idSpace.length() > 0) {
            idSpace = idSpace.toUpperCase() + ":";
        }
        return idSpace + classOntID;
    }

    private Label getLabel (OWLClass c, OWLOntology ont) {
        Label classLabel = new Label(null, null);
        for (OWLAnnotation annotation : c.getAnnotations(ont, this.dataFactory.getRDFSLabel())) {
            if (annotation.getValue() instanceof OWLLiteral) {
                OWLLiteral val = (OWLLiteral) annotation.getValue();

                classLabel.setText(val.getLiteral().replace("'", "\\'"));
                classLabel.setLang(val.getLang());
            }
        }
        return classLabel;
    }

    private void storeLabel (OWLClass c, String classUri) {
        Label classLabel = this.getLabel(c, this.ontology);

        if (StringUtils.isBlank(classLabel.text)) {
            Set<OWLOntology> importedOntologies = this.ontology.getImports();
            for (OWLOntology ont: importedOntologies) {
                classLabel = this.getLabel(c, ont);
                if (StringUtils.isNotEmpty(classLabel.text)) {
                    break;
                }
            }
        }

        if (StringUtils.isNotBlank(classLabel.text)) {
            setProperty(
                CLASS_NODE_LABEL,
                classUri,
                "rdfs:label",
                classLabel.text
            );
        }

        if (StringUtils.isNoneBlank(classLabel.lang)) {
            setProperty(
                CLASS_NODE_LABEL,
                classUri,
                "labelLang",
                classLabel.lang
            );
        }
    }

    private Set<OWLClass> getEquivalentClasses (OWLReasoner reasoner, OWLClass c) {
        Node<OWLClass> equivalentClasses = reasoner.getEquivalentClasses(c);
        Set<OWLClass> results;
        if (!c.isAnonymous()) {
            results = equivalentClasses.getEntities();
        } else {
            results = equivalentClasses.getEntitiesMinus(c.asOWLClass());
        }
        return results;
    }

    private void initTransaction () {
        // Fire empty statement to initialize transaction
        try {
            HttpResponse<JsonNode> response = Unirest.post(
                this.server_root_url + TRANSACTION_ENDPOINT)
                    .body("{\"statements\":[]}")
                    .asJson();
            Headers headers = response.getHeaders();
            String location = "";
            if (headers.containsKey("location")) {
                location = headers.get("location").toString();
                this.transaction = location.substring(
                    location.lastIndexOf("/"),
                    location.length() -1
                );
            }
            if (this.verbose_output) {
                System.out.println(
                    "Transaction initialized. Commit at " +
                        location +
                        " [Neo4J status:" +
                        Integer.toString(response.getStatus()) +
                        "]"
                );
            }
            checkForError(response);
        } catch (Exception e) {
            print_error(ANSI_RESET_DIM + "Error initiating transaction");
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    private void commitTransaction () {
        // Fire empty statement to initialize transaction
        try {
            HttpResponse<JsonNode> response = Unirest.post(
                this.server_root_url + TRANSACTION_ENDPOINT + this.transaction + "/commit")
                .body("{\"statements\":[]}")
                    .asJson();
            if (this.verbose_output) {
                System.out.println(
                    "Transaction committed. [Neo4J status:" +
                    Integer.toString(response.getStatus()) +
                    "]"
                );
            }
            checkForError(response);
        } catch (Exception e) {
            print_error(ANSI_RESET_DIM + "Error committing transaction");
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    private static void checkForError (HttpResponse<JsonNode> response) throws Exception {
        JSONObject jsonResponse = response.getBody().getObject();
        JSONArray errors = (JSONArray) jsonResponse.get("errors");
        if (errors.length() > 0) {
            JSONObject error = (JSONObject) errors.get(0);
            String errorMsg = error.get("code").toString() + ": \"" + error.get("message").toString() + "\"";
            throw new Exception(errorMsg);
        }
    }

    private void createNode (String classLabel, String classOntID, String classUri) {
        // Uniqueness for Class nodes needs to be defined before
        // Look: cypher/constraints.cql
        // Example: cypher/createClass.cql
        String cql = "MERGE (n:`" + classLabel + "` {uri:{classUri}});";
        JsonObject json = Json.createObjectBuilder()
            .add("statements", Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                    .add("statement", cql)
                    .add("parameters", Json.createObjectBuilder()
                        .add("classUri", classUri)
                    )
                )
            )
            .build();
        queryNeo4J(json, this.server_root_url + TRANSACTION_ENDPOINT + this.transaction, "Error creating a node");
        setLabel(classLabel, "uri", classUri, this.ontology_acronym);
        setProperty(classLabel, classUri, "name", classOntID);
    }

    private void setLabel (String classLabel, String key, String value, String newLabel) {
        String cql = "MATCH (n:`" + classLabel + "` {" + key + ":{value}}) SET n :`" + newLabel + "`;";
        JsonObject json = Json.createObjectBuilder()
            .add("statements", Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                    .add("statement", cql)
                    .add("parameters", Json.createObjectBuilder()
                        .add("value", value)
                    )
                )
            )
            .build();
        queryNeo4J(json, this.server_root_url + TRANSACTION_ENDPOINT + this.transaction, "Error creating a label");
    }

    private void createRelationship (String srcLabel, String srcUri, String destLabel, String destUri, String relationship) {
        // Example: cypher/createRelationship.cql
        String cql = "MATCH (src:`" + srcLabel + "` {uri:{srcUri}}), (dest:`" + destLabel + "` {uri:{destUri}}) MERGE (src)-[:`" + relationship + "`]->(dest);";
        JsonObject json = Json.createObjectBuilder()
            .add("statements", Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                    .add("statement", cql)
                    .add("parameters", Json.createObjectBuilder()
                        .add("srcUri", srcUri)
                        .add("destUri", destUri)
                    )
                )
            )
            .build();
        queryNeo4J(json, this.server_root_url + TRANSACTION_ENDPOINT + this.transaction, "Error creating a relationship");
    }

    private void setProperty (String classLabel, String classUri, String propertyName, String propertyValue) {
        // Example: cypher/setProperty.cql
        String cql = "MATCH (n:`" + classLabel + "` {uri:{classUri}}) SET n.`" + propertyName + "` = {propertyValue};";
        JsonObject json = Json.createObjectBuilder()
            .add("statements", Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                    .add("statement", cql)
                    .add("parameters", Json.createObjectBuilder()
                        .add("classUri", classUri)
                        .add("propertyValue", propertyValue)
                    )
                )
            )
            .build();
        queryNeo4J(json, this.server_root_url + TRANSACTION_ENDPOINT + this.transaction, "Error creating a node property");
    }

    private void queryNeo4J (JsonObject json, String url, String errorTitle) {
        try {
            HttpResponse<JsonNode> response = Unirest.post(url)
                .body(json.toString())
                .asJson();

            if (this.verbose_output) {
                System.out.println("CQL: " + json);
                this.cqlLogger.info(json.toString());
            }

            checkForError(response);
        } catch (Exception e) {
            print_error(ANSI_RESET_DIM + errorTitle);
            print_error("CQL: " + json);
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Command line parser
     */
    private void parseCommandLineArguments(String[] args)
    {
        CommandLine cl;

        Options meta_options = new Options();
        Options call_options = new Options();
        Options batch_options = new Options();
        Options all_options = new Options();


        Option help = Option.builder("h")
            .longOpt("help")
            .desc("Shows this help")
            .build();

        Option version = Option.builder()
            .longOpt("version")
            .desc("Show version")
            .build();

        Option verbosity = Option.builder("v")
            .longOpt("verbosity")
            .desc("Verbose output")
            .build();

        Option owl = Option.builder("o")
            .argName("Path")
            .hasArg()
            .numberOfArgs(1)
            .type(String.class)
            .required()
            .longOpt("owl")
            .desc("Path to OWL file")
            .build();

        Option name = Option.builder("n")
            .argName("String")
            .hasArg()
            .numberOfArgs(1)
            .type(String.class)
            .required()
            .longOpt("name")
            .desc("Ontology name (E.g. Gene Ontology)")
            .build();

        Option acronym = Option.builder("a")
            .argName("String")
            .hasArg()
            .numberOfArgs(1)
            .type(String.class)
            .required()
            .longOpt("abbreviation")
            .desc("Ontology abbreviation (E.g. go)")
            .build();

        Option server = Option.builder("s")
            .argName("URL")
            .hasArg()
            .numberOfArgs(1)
            .type(String.class)
            .longOpt("server")
            .desc("Neo4J server root URL [Default: http://localhost:7474]")
            .build();

        Option user = Option.builder("u")
            .argName("String")
            .hasArg()
            .numberOfArgs(1)
            .type(String.class)
            .longOpt("user")
            .desc("Neo4J user name")
            .build();

        Option password = Option.builder("p")
            .argName("String")
            .hasArg()
            .numberOfArgs(1)
            .type(String.class)
            .longOpt("password")
            .desc("Neo4J user password")
            .build();

        Option eqp = Option.builder()
            .argName("String")
            .hasArg()
            .numberOfArgs(Option.UNLIMITED_VALUES)
            .type(String.class)
            .longOpt("eqp")
            .desc("Existential quantification property (E.g. http://www.co-ode.org/ontologies/pizza/pizza.owl#hasTopping)")
            .build();

        Option batch = Option.builder("b")
            .argName("Path")
            .hasArg()
            .numberOfArgs(1)
            .type(String.class)
            .required()
            .longOpt("batch")
            .desc("Path to JSON file")
            .build();

        Option includeOwlImports = Option.builder("i")
            .longOpt("incl-imports")
            .desc("Include import closure")
            .build();

        Option noLocalOntologies = Option.builder("l")
            .longOpt("no-local")
            .desc("Don't scan for locally available OWL files to ensure loading remote files.")
            .build();

        all_options.addOption(help);
        all_options.addOption(version);
        all_options.addOption(verbosity);
        all_options.addOption(owl);
        all_options.addOption(name);
        all_options.addOption(acronym);
        all_options.addOption(server);
        all_options.addOption(user);
        all_options.addOption(password);
        all_options.addOption(eqp);
        all_options.addOption(batch);
        all_options.addOption(includeOwlImports);
        all_options.addOption(noLocalOntologies);

        meta_options.addOption(help);
        meta_options.addOption(version);

        call_options.addOption(owl);
        call_options.addOption(name);
        call_options.addOption(acronym);
        call_options.addOption(server);
        call_options.addOption(user);
        call_options.addOption(password);
        call_options.addOption(verbosity);
        call_options.addOption(eqp);
        call_options.addOption(includeOwlImports);
        call_options.addOption(noLocalOntologies);

        batch_options.addOption(batch);
        batch_options.addOption(verbosity);

        try {
            // Parse only for meta options, e.g. `-h` and `-v`
            cl = new DefaultParser().parse(meta_options, args, true);
            if (cl.getOptions().length > 0) {
                if (cl.hasOption("h")) {
                    usage(all_options);
                }
                if (cl.hasOption("version")) {
                    System.out.println(VERSION);
                }
                // Exit the program whenever a meta option was found as meta and call options should be mutually exclusive
                System.exit(0);
            }
        }  catch (ParseException e) {
            print_error("Error parsing command line meta options");
            print_error(e.getMessage());
            System.out.println("\n");
            usage(all_options);
            System.exit(1);
        }

        boolean batchImport;

        try {
            // Parse only for batch options, e.g. `-b`
            cl = new DefaultParser().parse(batch_options, args, true);

            this.path_to_batch = cl.getOptionValue("b");

            if (cl.hasOption("v")) {
                this.verbose_output = true;
            }
            batchImport = true;
        }  catch (ParseException e) {
            batchImport = false;
        }

        if (!batchImport) {
            try {
                cl = new DefaultParser().parse(call_options, args);

                this.path_to_owl = cl.getOptionValue("o");
                this.ontology_name = cl.getOptionValue("n");
                this.ontology_acronym = cl.getOptionValue("a").toUpperCase();
                this.include_import_closure = cl.hasOption("i");
                this.no_local_owl_files = cl.hasOption("l");
                this.server_root_url = cl.getOptionValue("s", "http://localhost:7474");
                this.neo4j_authentication_header = "Basic: " + Base64.encodeBase64String((cl.getOptionValue("u") + ":" + cl.getOptionValue("p")).getBytes());

                if (cl.hasOption("eqp")) {
                    this.eqps = new HashSet<>(Arrays.asList(cl.getOptionValues("eqp")));
                }

                if (cl.hasOption("v")) {
                    this.verbose_output = true;
                }
            } catch (ParseException e) {
                print_error("Error parsing command line call options");
                print_error(e.getMessage());
                System.out.println("\n");
                usage(all_options);
                System.exit(1);
            }
        }
    }

    /**
     * Prints a usage message to the console.
     */
    public static void usage(Options options) {
        String header = "Import OWL into Neo4J as a labeled property graph.\n\n";
        String footer = "\nPlease report issues at http://github.com/flekschas/owl2neo4j/issues";

        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java -jar owl2neo4j.jar", header, options, footer, true);
    }

    /**
     * Prints error message in red.
     */
    public static void print_error(String message) {
        System.err.println(ANSI_RED + message + ANSI_RESET);
    }
}
