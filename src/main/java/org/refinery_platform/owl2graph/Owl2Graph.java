package org.refinery_platform.owl2graph;

/** OWL API */
import com.hp.hpl.jena.ontology.*;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.reasoner.Reasoner;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;
import com.hp.hpl.jena.util.iterator.Filter;

/** Apache commons */
import org.apache.commons.cli.*;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;

/** Jersey RESTful client */
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.Headers;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.exceptions.UnirestException;

/** JSON **/
import org.json.JSONObject;
import org.json.JSONArray;
import org.mindswap.pellet.jena.PelletReasonerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;


public class Owl2Graph {

    private static String REST_ENDPOINT = "/db/data";
    private static String TRANSACTION_ENDPOINT = "/db/data/transaction";

    public static String ROOT_ONTOLOGY = "owl";
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

    private String path_to_owl;
    private String ontology_name;
    private String ontology_acronym;
    private String server_root_url;
    private String neo4j_authentication_header;
    private String transaction;

    private OntModel model;
    private String ontUri;

    private Logger cqlLogger;
    private FileHandler fh;
    private Boolean verbose_output = false;

    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";

    public static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        Owl2Graph ont = new Owl2Graph(args);

        Unirest.setDefaultHeader("Content-type", "application/json");
        Unirest.setDefaultHeader("Accept", "application/json; charset=UTF-8");
        // Yields better performance and reduces memory load on the Neo4J server
        // http://neo4j.com/docs/stable/rest-api-streaming.html
        Unirest.setDefaultHeader("X-Stream", "true");
        Unirest.setDefaultHeader(
            "Authorization", ont.neo4j_authentication_header
        );

        // Test if server is available
        try {
            HttpResponse<JsonNode> response = Unirest.get(
                ont.server_root_url
            ).asJson();
            System.out.println(
                "Neo4J status: " + Integer.toString(response.getStatus())
            );
        } catch (Exception e) {
            print_error("Error querying Neo4J server root URL");
            print_error(e.getMessage());
            System.exit(1);
        }

        // Try authentication
        try {
            HttpResponse<JsonNode> response = Unirest.get(
                ont.server_root_url + REST_ENDPOINT
            ).asJson();
            System.out.println(
                "REST endpoint status: " +
                Integer.toString(response.getStatus())
            );
        } catch (Exception e) {
            print_error("Error querying Neo4J REST endpoint");
            print_error(e.getMessage());
            System.exit(1);
        }

        long loadTimeSec = -1;
        double loadTimeMin = -1.0;

        try {
            long start = System.nanoTime();
            ont.loadOntology();
            long end = System.nanoTime();
            loadTimeSec = TimeUnit.NANOSECONDS.toSeconds(end - start);
            loadTimeMin = TimeUnit.NANOSECONDS.toMinutes(end - start);
            System.out.println("Successfully loaded ontology");
        } catch (Exception e) {
            print_error("Error loading the ontology");
            print_error(e.getMessage());
            System.exit(1);
        }

        long importTimeSec = -1;
        double importTimeMin = -1;
        try {
            long start = System.nanoTime();
            ont.importOntology();
            long end = System.nanoTime();
            importTimeSec = TimeUnit.NANOSECONDS.toSeconds(end - start);
            importTimeMin = TimeUnit.NANOSECONDS.toMinutes(end - start);
            System.out.println("Successfully imported ontology");
        } catch (Exception e) {
            print_error("Error importing the ontology");
            print_error(e.getMessage());
            System.exit(1);
        }

        // Unirest has be closed explicitly
        try {
            Unirest.shutdown();
        } catch (Exception e) {
            print_error("Error shutting down Unirest");
            print_error(e.getMessage());
            System.exit(1);
        }

        // Print some performance related numbers
        if (ont.verbose_output) {
            System.out.println("-----");
            System.out.println(
                "Load time:   " +
                Double.toString(loadTimeMin) +
                "min (" +
                Long.toString(loadTimeSec) +
                "s)"
            );
            System.out.println(
                "Import time: " +
                Double.toString(importTimeMin) +
                "min (" +
                Long.toString(importTimeSec) +
                "s)");
        }
    }

    public Owl2Graph(String[] args) {
        parseCommandLineArguments(args);
    }

    public void loadOntology() throws Exception {
        // Using Pellet for reasoning.
        this.model = ModelFactory.createOntologyModel(PelletReasonerFactory.THE_SPEC);

        // For some reason this settings reasoned that some classes have no parent, which seems weird.
        // Example: Pizza Ontology: #MeatyPizza has no parent although it has a super class without reasoning and even
        // with reasoning (tested with HermiT in Protege).
        //this.model = ModelFactory.createOntologyModel(OntModelSpec.OWL_DL_MEM_TRANS_INF);

        try {
            InputStream in = new FileInputStream(this.path_to_owl); // or any windows path
            this.model.read(in, null);
            in.close();

            // Get URI for the source ontology to be imported.
            String ontUriPrefix = this.model.getNsPrefixURI("");
            this.ontUri = ontUriPrefix;
            if (this.ontUri.charAt(this.ontUri.length() - 1) == '#') {
                this.ontUri = this.ontUri.substring(0, this.ontUri.length() - 1);
            }

            // Add prefix for to the PrefixMapping for the ontology to be imported
            this.model.setNsPrefix(this.ontology_acronym, ontUriPrefix);
            // Remove the empty prefix otherwise it will match URIs and append an empty prefix.
            this.model.removeNsPrefix("");
        } catch (Exception e) {
            print_error("Error loading the ontology");
            print_error(e.getMessage());
            System.exit(1);
        }

        System.out.println("Ontology Loaded!");
        System.out.println("Ontology URI:    " + this.ontUri);
    }

    private void importOntology() throws Exception
    {
        // Init Cypher logger
        this.cqlLogger = Logger.getLogger("Cypher:" + this.ontology_acronym);
        if (this.verbose_output) {
            try {
                // Create at most five 10MB logger.
                this.fh = new FileHandler("Cypher log for " + this.ontology_acronym + ".log", 10485760, 5);
                this.cqlLogger.addHandler(fh);
                SimpleFormatter formatter = new SimpleFormatter();
                fh.setFormatter(formatter);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // This blog is heavily inspired by:
        // http://neo4j.com/blog/and-now-for-something-completely-different-using-owl-with-neo4j/
        try {
            initTransaction();

            // Create a node for the ontology
            createNode(
                ONTOLOGY_NODE_LABEL,
                this.ontology_acronym,
                this.ontUri
            );
            setProperty(
                ONTOLOGY_NODE_LABEL,
                this.ontUri,
                "rdfs:label",
                this.ontology_name
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

            // Create root node "owl:Thing"
//            createNode(
//                CLASS_NODE_LABEL,
//                ROOT_CLASS_ONT_ID,
//                ROOT_CLASS_URI
//            );


            // Set of variables used in each loop.
            OntClass klass;
            OntClass superKlass;
            String klassLabel;
            String klassOntID;
            String klassUri;
            String superKlassOntID;
            String superKlassUri;

            // Iterate over classes that are a URI resource only
            // Why: http://stackoverflow.com/a/24566750/981933
            ExtendedIterator<OntClass> it = this.model.listClasses().filterKeep( new Filter<OntClass>() {
                @Override
                public boolean accept(OntClass o) {
                return o.isURIResource();
                }
            });

            while ( it.hasNext() ) {
                klass = it.next();

                klassUri = klass.getURI();
                klassOntID = createOntID(klass);

                createNode(CLASS_NODE_LABEL, klassOntID, klassUri);

                // Get class label
                klassLabel = klass.getLabel("EN");
                if (StringUtils.isNotEmpty(klassLabel)) {
                    setProperty(
                        CLASS_NODE_LABEL,
                        klassUri,
                        "rdfs:label",
                        klassLabel.replace("'", "\\'")
                    );
                }

                ExtendedIterator<OntClass> jt = klass.listSuperClasses(true).filterKeep( new Filter<OntClass>() {
                    @Override
                    public boolean accept(OntClass o) {
                    return o.isURIResource();
                    }
                });

                while ( jt.hasNext() ) {
                    superKlass = jt.next();
                    superKlassUri = superKlass.getURI();
                    superKlassOntID = createOntID(superKlass);

                    createNode(
                        CLASS_NODE_LABEL,
                        superKlassOntID,
                        superKlassUri
                    );

                    createRelationship(
                        CLASS_NODE_LABEL,
                        klassUri,
                        CLASS_NODE_LABEL,
                        superKlassUri,
                        "rdfs:subClassOf"
                    );
                }
            }
            commitTransaction();
        } catch (Exception e) {
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    public String createOntID (OntClass klass) {
        if (StringUtils.isNotEmpty(this.model.getNsURIPrefix(klass.getNameSpace()))) {
            return this.model.shortForm(klass.getURI());
        }
        return klass.getLocalName();
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
                    location.length() - 1
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
        } catch (Exception e) {
            print_error("Error starting transaction");
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
            JSONObject jsonResponse = response.getBody().getObject();
            JSONArray errors = (JSONArray) jsonResponse.get("errors");
            if (errors.length() > 0) {
                JSONObject error = (JSONObject) errors.get(0);
                String errorMsg = error.get("message").toString();
                if (this.verbose_output) {
                    errorMsg = response.getBody().toString();
                }
                throw new Exception(errorMsg);
            }
        } catch (Exception e) {
            print_error("Error committing transaction");
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    private void createNode (String klassLabel, String klassOntID, String klassUri) {
        // Uniqueness for Class nodes needs to be defined before
        // Look: cypher/constraints.cql
        // Example: cypher/createClass.cql
        try {
            String cql = "MERGE (n:" + klassLabel + " {name:'" + klassOntID + "',uri:'" + klassUri + "'}) SET n :" + this.ontology_acronym + ";";
            HttpResponse<JsonNode> response = Unirest.post(this.server_root_url + TRANSACTION_ENDPOINT + this.transaction)
                .body("{\"statements\":[{\"statement\":\"" + cql + "\"}]}")
                    .asJson();
            if (this.verbose_output) {
                System.out.println("CQL: `" + cql + "` [Neo4J status:" + Integer.toString(response.getStatus()) + "]");
                this.cqlLogger.info(cql);
            }
        } catch (UnirestException e) {
            print_error("Error creating a node");
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    private void createRelationship (String srcLabel, String srcUri, String destLabel, String destUri, String relationship) {
        // Example: cypher/createRelationship.cql
        try {
            String cql = "MATCH (src:" + srcLabel + " {uri:'" + srcUri + "'}), (dest:" + destLabel + " {uri:'" + destUri + "'}) MERGE (src)-[:`" + relationship + "`]->(dest);";
            HttpResponse<JsonNode> response = Unirest.post(this.server_root_url + TRANSACTION_ENDPOINT + this.transaction)
                    .body("{\"statements\":[{\"statement\":\"" + cql + "\"}]}")
                    .asJson();
            if (this.verbose_output) {
                System.out.println("CQL: `" + cql + "`  [Neo4J status: " + Integer.toString(response.getStatus()) + "]");
                this.cqlLogger.info(cql);
            }
        } catch (UnirestException e) {
            print_error("Error creating a relationship");
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    private void setProperty (String klassLabel, String klassUri, String propertyName, String propertyValue) {
        // Example: cypher/setProperty.cql
        try {
            String cql = "MATCH (n:" + klassLabel + " {uri:'" + klassUri + "'}) SET n.`" + propertyName + "` = '" + propertyValue + "';";
            HttpResponse<JsonNode> response = Unirest.post(this.server_root_url + TRANSACTION_ENDPOINT + this.transaction)
                    .body("{\"statements\":[{\"statement\":\"" + cql + "\"}]}")
                    .asJson();
            if (this.verbose_output) {
                System.out.println("CQL: `" + cql + "` [Neo4J status: " + Integer.toString(response.getStatus()) + "]");
                this.cqlLogger.info(cql);
            }
        } catch (UnirestException e) {
            print_error("Error creating a node property");
            print_error(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Command line parser
     * @param args
     */
    private void parseCommandLineArguments(String[] args)
    {
        CommandLine cl;

        Options meta_options = new Options();
        Options call_options = new Options();
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
            .desc("Ontology abbreviation (E.g. GO)")
            .build();

        Option server = Option.builder("s")
            .argName("URL")
            .hasArg()
            .numberOfArgs(1)
            .type(String.class)
            .required()
            .longOpt("server")
            .desc("Neo4J server root URL")
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

        all_options.addOption(help);
        all_options.addOption(version);
        all_options.addOption(verbosity);
        all_options.addOption(owl);
        all_options.addOption(name);
        all_options.addOption(acronym);
        all_options.addOption(server);
        all_options.addOption(user);
        all_options.addOption(password);

        meta_options.addOption(help);
        meta_options.addOption(version);

        call_options.addOption(owl);
        call_options.addOption(name);
        call_options.addOption(acronym);
        call_options.addOption(server);
        call_options.addOption(user);
        call_options.addOption(password);
        call_options.addOption(verbosity);

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

        try {
            cl = new DefaultParser().parse(call_options, args);
            this.path_to_owl = cl.getOptionValue("o");
            this.server_root_url = cl.getOptionValue("s").substring(0, cl.getOptionValue("s").length() - (cl.getOptionValue("s").endsWith("/") ? 1 : 0));
            this.ontology_name = cl.getOptionValue("n");
            this.ontology_acronym = cl.getOptionValue("a");
            this.neo4j_authentication_header = "Basic: " + Base64.encodeBase64String((cl.getOptionValue("u") + ":" + cl.getOptionValue("p")).getBytes());
            if (cl.hasOption("v")) {
                this.verbose_output = true;
            }
        }  catch (ParseException e) {
            print_error("Error parsing command line call options");
            print_error(e.getMessage());
            System.out.println("\n");
            usage(all_options);
            System.exit(1);
        }
    }

    /**
     * Prints a usage message to the console.
     */
    public static void usage(Options options) {
        String header = "Import OWL into Neo4J as a labeled property graph.\n\n";
        String footer = "\nPlease report issues at http://github.com/flekschas/owl2neo4j/issues";

        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java -jar Owl2Graph.jar", header, options, footer, true);
    }

    /**
     * Prints error message in red.
     */
    public static void print_error(String message) {
        System.err.println(ANSI_RED + message + ANSI_RESET);
    }
}
