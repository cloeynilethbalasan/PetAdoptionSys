<?php
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
ini_set('display_errors', '0');
mysqli_report(MYSQLI_REPORT_ERROR | MYSQLI_REPORT_STRICT);

function sendJson(array $data, int $status = 200): void {
    http_response_code($status);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
    exit;
}

function validAdopterId($raw): int {
    $id = filter_var($raw, FILTER_VALIDATE_INT, ['options' => ['min_range' => 1]]);
    if ($id === false || $id === null) {
        sendJson(['success' => false, 'message' => 'Choose a valid adopter.'], 400);
    }
    return (int)$id;
}

function choice(array $input, string $key, array $allowed): string {
    $value = (string)($input[$key] ?? '');
    if (!in_array($value, $allowed, true)) {
        sendJson(['success' => false, 'message' => 'Please answer every quiz question.'], 400);
    }
    return $value;
}

function energyLevel(?string $personality, ?string $behavior): int {
    $text = strtolower(($personality ?? '') . ' ' . ($behavior ?? ''));
    foreach (['energetic', 'active', 'outgoing', 'fetch', 'explor', 'play', 'guard', 'protective', 'alert'] as $word) {
        if (str_contains($text, $word)) return 3;
    }
    foreach (['calm', 'quiet', 'gentle', 'relax', 'independent', 'lap', 'cuddl', 'indoor', 'affectionate'] as $word) {
        if (str_contains($text, $word)) return 1;
    }
    return 2;
}

function matchPet(array $pet, array $answers): array {
    $level = energyLevel($pet['Personality'], $pet['Behavior']);
    $activity = ['Calm' => 1, 'Balanced' => 2, 'Active' => 3][$answers['activity']];
    $points = $answers['species'] === 'Any' ? 25 : 30;
    $distance = abs($level - $activity);
    $points += $distance === 0 ? 30 : ($distance === 1 ? 15 : 5);

    if ($answers['home'] === 'Quiet') {
        $points += $level === 1 ? 20 : ($level === 2 ? 12 : 6);
    } else {
        $points += $level === 3 ? 20 : ($level === 2 ? 14 : 8);
    }

    $profileText = strtolower(($pet['Personality'] ?? '') . ' ' . ($pet['Behavior'] ?? ''));
    $mentionsChildren = str_contains($profileText, 'children') || str_contains($profileText, 'kids');
    $points += $answers['children'] === 'Yes' ? ($mentionsChildren ? 15 : 8) : 12;

    if ($answers['experience'] === 'First-time') {
        $points += $level === 1 ? 15 : ($level === 2 ? 11 : 6);
    } else {
        $points += $level === 3 ? 15 : ($level === 2 ? 11 : 10);
    }

    $petEnergy = [1 => 'calm', 2 => 'balanced', 3 => 'active'][$level];
    $factors = [
        $answers['species'] === 'Any' ? 'Open species preference' : 'Preferred species: ' . $answers['species'],
        'Pet activity: ' . $petEnergy,
        'Home: ' . strtolower($answers['home'])
    ];
    if ($answers['children'] === 'Yes' && $mentionsChildren) {
        $factors[] = 'Profile mentions children';
    }

    $pet['CompatibilityScore'] = round($points * 100 / 110, 2);
    $pet['MatchingFactors'] = implode('; ', $factors);
    $pet['ActivityProfile'] = ucfirst($petEnergy);
    return $pet;
}

$inTransaction = false;
try {
    require_once __DIR__ . '/database.php';
    if (!isset($conn) || !($conn instanceof mysqli)) {
        throw new RuntimeException('database.php must define a mysqli connection called $conn.');
    }
    $conn->set_charset('utf8mb4');
    $action = (string)($_GET['action'] ?? 'options');
    $method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

    if ($method === 'GET' && $action === 'options') {
        $adopters = $conn->query(
            "SELECT AdopterID, FirstName, LastName FROM Adopters WHERE Status = 'Active' ORDER BY FirstName, LastName"
        )->fetch_all(MYSQLI_ASSOC);
        $count = $conn->query(
            "SELECT COUNT(*) AS total FROM Pets p WHERE p.AdoptionStatus = 'Available' " .
            "AND NOT EXISTS (SELECT 1 FROM Adoptions a WHERE a.PetID = p.PetID)"
        )->fetch_assoc();
        sendJson(['success' => true, 'adopters' => $adopters, 'available_pets' => (int)$count['total']]);
    }

    if ($method === 'GET' && $action === 'history') {
        $where = '';
        $id = null;
        if (isset($_GET['adopter_id']) && $_GET['adopter_id'] !== '') {
            $id = validAdopterId($_GET['adopter_id']);
            $where = ' WHERE cr.AdopterID = ?';
        }
        $sql = 'SELECT cr.CompatibilityID, cr.AdopterID, ad.FirstName, ad.LastName, ' .
               'cr.RecommendedPetID, p.PetName, p.Species, cr.CompatibilityScore, ' .
               'cr.MatchingFactors, cr.RecommendationDate ' .
               'FROM Compatibility_Results cr ' .
               'JOIN Adopters ad ON ad.AdopterID = cr.AdopterID ' .
               'JOIN Pets p ON p.PetID = cr.RecommendedPetID ' .
               $where . ' ORDER BY cr.CompatibilityID DESC LIMIT 50';
        $stmt = $conn->prepare($sql);
        if ($id !== null) $stmt->bind_param('i', $id);
        $stmt->execute();
        $history = $stmt->get_result()->fetch_all(MYSQLI_ASSOC);
        $stmt->close();
        sendJson(['success' => true, 'history' => $history]);
    }

    if ($method !== 'POST' || $action !== 'match') {
        sendJson(['success' => false, 'message' => 'Unknown action or HTTP method.'], 405);
    }

    $input = json_decode(file_get_contents('php://input'), true);
    if (!is_array($input)) sendJson(['success' => false, 'message' => 'Invalid JSON body.'], 400);
    $adopterId = validAdopterId($input['adopter_id'] ?? null);
    $answers = [
        'species' => choice($input, 'species', ['Any', 'Dog', 'Cat', 'Rabbit', 'Hamster', 'Bird']),
        'activity' => choice($input, 'activity', ['Calm', 'Balanced', 'Active']),
        'home' => choice($input, 'home', ['Quiet', 'Active']),
        'children' => choice($input, 'children', ['Yes', 'No']),
        'experience' => choice($input, 'experience', ['First-time', 'Experienced'])
    ];

    $stmt = $conn->prepare("SELECT AdopterID FROM Adopters WHERE AdopterID = ? AND Status = 'Active'");
    $stmt->bind_param('i', $adopterId);
    $stmt->execute();
    $adopter = $stmt->get_result()->fetch_assoc();
    $stmt->close();
    if (!$adopter) sendJson(['success' => false, 'message' => 'Select an active registered adopter.'], 400);

    $result = $conn->query(
        'SELECT p.PetID, p.PetName, p.Species, p.Breed, p.Age, p.Gender, ' .
        'pp.Personality, pp.Behavior, pp.HealthStatus, pp.VaccinationStatus ' .
        'FROM Pets p LEFT JOIN Pet_Profiles pp ON pp.ProfileID = ' .
        '(SELECT MIN(x.ProfileID) FROM Pet_Profiles x WHERE x.PetID = p.PetID) ' .
        "WHERE p.AdoptionStatus = 'Available' " .
        'AND NOT EXISTS (SELECT 1 FROM Adoptions a WHERE a.PetID = p.PetID) ' .
        'ORDER BY p.PetID'
    );

    $matches = [];
    foreach ($result->fetch_all(MYSQLI_ASSOC) as $pet) {
        if ($answers['species'] !== 'Any' && strcasecmp($answers['species'], (string)$pet['Species']) !== 0) {
            continue;
        }
        $matches[] = matchPet($pet, $answers);
    }
    usort($matches, function ($a, $b) {
        return ($b['CompatibilityScore'] <=> $a['CompatibilityScore']) ?: ($a['PetID'] <=> $b['PetID']);
    });
    if (count($matches) === 0) {
        sendJson(['success' => true, 'matches' => [], 'message' => 'No eligible pets match the selected species right now. Nothing was saved.']);
    }

    $best = $matches[0];
    $petId = (int)$best['PetID'];
    $score = (float)$best['CompatibilityScore'];
    $factors = $best['MatchingFactors'];

    $conn->begin_transaction();
    $inTransaction = true;
    $stmt = $conn->prepare('SELECT AdoptionStatus FROM Pets WHERE PetID = ? FOR UPDATE');
    $stmt->bind_param('i', $petId);
    $stmt->execute();
    $latestPet = $stmt->get_result()->fetch_assoc();
    $stmt->close();
    $stmt = $conn->prepare('SELECT AdoptionID FROM Adoptions WHERE PetID = ? LIMIT 1');
    $stmt->bind_param('i', $petId);
    $stmt->execute();
    $adoption = $stmt->get_result()->fetch_assoc();
    $stmt->close();
    if (!$latestPet || $latestPet['AdoptionStatus'] !== 'Available' || $adoption) {
        $conn->rollback();
        $inTransaction = false;
        sendJson(['success' => false, 'message' => 'Pet availability changed. Please retake the quiz.'], 409);
    }
    $stmt = $conn->prepare(
        'INSERT INTO Compatibility_Results ' .
        '(AdopterID, RecommendedPetID, CompatibilityScore, MatchingFactors, RecommendationDate) ' .
        'VALUES (?, ?, ?, ?, CURDATE())'
    );
    $stmt->bind_param('iids', $adopterId, $petId, $score, $factors);
    $stmt->execute();
    $resultId = $conn->insert_id;
    $stmt->close();
    $conn->commit();
    $inTransaction = false;

    sendJson([
        'success' => true,
        'message' => 'Quiz completed. The top recommendation was saved.',
        'compatibility_id' => $resultId,
        'matches' => array_slice($matches, 0, 3)
    ]);
} catch (Throwable $e) {
    if ($inTransaction && isset($conn) && $conn instanceof mysqli) {
        try { $conn->rollback(); } catch (Throwable $ignored) {}
    }
    error_log('compatibility_api.php: ' . $e->getMessage());
    sendJson(['success' => false, 'message' => 'Server/database error. Check database.php and your PHP error log.'], 500);
}
