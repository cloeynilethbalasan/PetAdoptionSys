import java.sql.*;
import java.util.*;

public class CompatibilityController {
    private static final String URL = "jdbc:mysql://127.0.0.1:3307/petadoptiondb";
    private static final String USER = "root";
    private static final String PASSWORD = "";

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static class PetMatch {
        int petId;
        String name, species, breed, personality, behavior;
        String factors;
        int activityLevel;
        double score;
    }

    private static boolean containsAny(String input, String... words) {
        for (String word : words) if (input.contains(word)) return true;
        return false;
    }

    private static int petActivity(String personality, String behavior) {
        String text = ((personality == null ? "" : personality) + " " +
                       (behavior == null ? "" : behavior)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "energetic", "active", "outgoing", "fetch", "explor",
                "play", "guard", "protective", "alert")) return 3;
        if (containsAny(text, "calm", "quiet", "gentle", "relax", "independent",
                "lap", "cuddl", "indoor", "affectionate")) return 1;
        return 2;
    }

    private static PetMatch scorePet(PetMatch pet, String species, String activity,
                                     String home, String children, String experience) {
        int level = petActivity(pet.personality, pet.behavior);
        pet.activityLevel = level;
        int requestedLevel = activity.equals("Calm") ? 1 : (activity.equals("Balanced") ? 2 : 3);
        int points = species.equals("Any") ? 25 : 30;
        int difference = Math.abs(level - requestedLevel);
        points += difference == 0 ? 30 : (difference == 1 ? 15 : 5);
        if (home.equals("Quiet")) {
            points += level == 1 ? 20 : (level == 2 ? 12 : 6);
        } else {
            points += level == 3 ? 20 : (level == 2 ? 14 : 8);
        }
        String profile = ((pet.personality == null ? "" : pet.personality) + " " +
                          (pet.behavior == null ? "" : pet.behavior)).toLowerCase(Locale.ROOT);
        boolean mentionsChildren = containsAny(profile, "children", "kids");
        points += children.equals("Yes") ? (mentionsChildren ? 15 : 8) : 12;
        if (experience.equals("First-time")) {
            points += level == 1 ? 15 : (level == 2 ? 11 : 6);
        } else {
            points += level == 3 ? 15 : (level == 2 ? 11 : 10);
        }
        String energy = level == 1 ? "calm" : (level == 2 ? "balanced" : "active");
        pet.factors = (species.equals("Any") ? "Open species preference" : "Preferred species: " + species)
                + "; Pet activity: " + energy + "; Home: " + home.toLowerCase(Locale.ROOT)
                + (children.equals("Yes") && mentionsChildren ? "; Profile mentions children" : "");
        pet.score = Math.round(points * 10000.0 / 110.0) / 100.0;
        return pet;
    }

    private static boolean allowed(String value, String... options) {
        for (String option : options) if (option.equals(value)) return true;
        return false;
    }

    private static List<PetMatch> eligiblePets(Connection conn) throws SQLException {
        String sql = "SELECT p.PetID, p.PetName, p.Species, p.Breed, pp.Personality, pp.Behavior " +
            "FROM Pets p LEFT JOIN Pet_Profiles pp ON pp.ProfileID = " +
            "(SELECT MIN(x.ProfileID) FROM Pet_Profiles x WHERE x.PetID = p.PetID) " +
            "WHERE p.AdoptionStatus = 'Available' " +
            "AND NOT EXISTS (SELECT 1 FROM Adoptions a WHERE a.PetID = p.PetID) " +
            "ORDER BY p.PetID";
        List<PetMatch> pets = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                PetMatch pet = new PetMatch();
                pet.petId = rs.getInt("PetID");
                pet.name = rs.getString("PetName");
                pet.species = rs.getString("Species");
                pet.breed = rs.getString("Breed");
                pet.personality = rs.getString("Personality");
                pet.behavior = rs.getString("Behavior");
                pets.add(pet);
            }
        }
        return pets;
    }

    private static boolean activeAdopter(Connection conn, int adopterId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT AdopterID FROM Adopters WHERE AdopterID = ? AND Status = 'Active'")) {
            stmt.setInt(1, adopterId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void quiz(int adopterId, String species, String activity, String home,
                             String children, String experience) {
        if (!allowed(species, "Any", "Dog", "Cat", "Rabbit", "Hamster", "Bird") ||
            !allowed(activity, "Calm", "Balanced", "Active") ||
            !allowed(home, "Quiet", "Active") ||
            !allowed(children, "Yes", "No") ||
            !allowed(experience, "First-time", "Experienced")) {
            System.out.println("Invalid quiz choices. See command help below.");
            help();
            return;
        }

        try (Connection conn = connect()) {
            if (!activeAdopter(conn, adopterId)) {
                System.out.println("Select an active adopter ID.");
                return;
            }
            List<PetMatch> matches = new ArrayList<>();
            for (PetMatch pet : eligiblePets(conn)) {
                if (!species.equals("Any") && !species.equalsIgnoreCase(pet.species)) continue;
                matches.add(scorePet(pet, species, activity, home, children, experience));
            }
            matches.sort(Comparator.comparingDouble((PetMatch p) -> p.score).reversed()
                    .thenComparingInt(p -> p.petId));
            if (matches.isEmpty()) {
                System.out.println("No eligible pets match the selected species. Nothing was saved.");
                return;
            }

            PetMatch best = matches.get(0);
            conn.setAutoCommit(false);
            try {
                String status;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT AdoptionStatus FROM Pets WHERE PetID = ? FOR UPDATE")) {
                    stmt.setInt(1, best.petId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Selected pet not found.");
                        status = rs.getString("AdoptionStatus");
                    }
                }
                if (!"Available".equals(status)) {
                    throw new SQLException("Pet availability changed. Please retry the quiz.");
                }
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT AdoptionID FROM Adoptions WHERE PetID = ? LIMIT 1")) {
                    stmt.setInt(1, best.petId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) throw new SQLException("Pet has already been adopted.");
                    }
                }
                String insert = "INSERT INTO Compatibility_Results " +
                    "(AdopterID, RecommendedPetID, CompatibilityScore, MatchingFactors, RecommendationDate) " +
                    "VALUES (?, ?, ?, ?, CURDATE())";
                try (PreparedStatement stmt = conn.prepareStatement(insert)) {
                    stmt.setInt(1, adopterId);
                    stmt.setInt(2, best.petId);
                    stmt.setDouble(3, best.score);
                    stmt.setString(4, best.factors);
                    stmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                System.out.println("Cannot save recommendation: " + e.getMessage());
                return;
            }

            System.out.println("=== TOP PET MATCHES FOR ADOPTER " + adopterId + " ===");
            for (int i = 0; i < Math.min(3, matches.size()); i++) {
                PetMatch pet = matches.get(i);
                System.out.printf(Locale.ROOT, "%d. %s (%s, Pet #%d): %.2f%%%n",
                        i + 1, pet.name, pet.species, pet.petId, pet.score);
                System.out.println("   " + pet.factors);
            }
            System.out.println("Top recommendation saved in Compatibility_Results.");
        } catch (SQLException e) {
            System.out.println("Database error: " + e.getMessage());
        }
    }

    private static void history(Integer adopterId) {
        String sql = "SELECT cr.CompatibilityID, cr.AdopterID, ad.FirstName, ad.LastName, " +
            "p.PetName, cr.CompatibilityScore, cr.MatchingFactors, cr.RecommendationDate " +
            "FROM Compatibility_Results cr " +
            "JOIN Adopters ad ON ad.AdopterID = cr.AdopterID " +
            "JOIN Pets p ON p.PetID = cr.RecommendedPetID " +
            (adopterId == null ? "" : "WHERE cr.AdopterID = ? ") +
            "ORDER BY cr.CompatibilityID DESC LIMIT 50";
        try (Connection conn = connect(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (adopterId != null) stmt.setInt(1, adopterId);
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("=== SAVED COMPATIBILITY RESULTS ===");
                boolean found = false;
                while (rs.next()) {
                    found = true;
                    System.out.printf(Locale.ROOT, "Result #%d | %s %s (Adopter #%d) | %s | %.2f%% | %s%n",
                        rs.getInt("CompatibilityID"), rs.getString("FirstName"),
                        rs.getString("LastName"), rs.getInt("AdopterID"),
                        rs.getString("PetName"), rs.getDouble("CompatibilityScore"),
                        rs.getDate("RecommendationDate"));
                    System.out.println("  " + rs.getString("MatchingFactors"));
                }
                if (!found) System.out.println("No saved compatibility results.");
            }
        } catch (SQLException e) {
            System.out.println("Database error: " + e.getMessage());
        }
    }

    private static void help() {
        System.out.println("Usage:");
        System.out.println("  java -cp \".;lib/*\" CompatibilityController list");
        System.out.println("  java -cp \".;lib/*\" CompatibilityController adopter <ID>");
        System.out.println("  java -cp \".;lib/*\" CompatibilityController quiz <ID> <species> <activity> <home> <children> <experience>");
        System.out.println("Species: Any, Dog, Cat, Rabbit, Hamster, Bird");
        System.out.println("Activity: Calm, Balanced, Active | Home: Quiet, Active");
        System.out.println("Children: Yes, No | Experience: First-time, Experienced");
        System.out.println("Example: java -cp \".;lib/*\" CompatibilityController quiz 3 Cat Calm Quiet No First-time");
    }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
            history(null);
            return;
        }
        try {
            if (args.length == 2 && args[0].equalsIgnoreCase("adopter")) {
                int adopterId = Integer.parseInt(args[1]);
                if (adopterId <= 0) throw new NumberFormatException();
                history(adopterId);
            } else if (args.length == 7 && args[0].equalsIgnoreCase("quiz")) {
                int adopterId = Integer.parseInt(args[1]);
                if (adopterId <= 0) throw new NumberFormatException();
                quiz(adopterId, args[2], args[3], args[4], args[5], args[6]);
            } else help();
        } catch (NumberFormatException e) {
            System.out.println("Adopter ID must be a positive integer.");
        }
    }
}
