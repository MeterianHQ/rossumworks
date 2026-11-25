# Tutorial, Plan 1: the Sample and the Core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A template repository with a small checkout program, a script that creates its issues, and the core of the tutorial, proved by one full run.

**Architecture:** The sample is a new repository, `MeterianHQ/rossumworks-tutorial`, without `.factory`. The text of the tutorial is `docs/tutorial.md` in `rossumworks`. The factory code does not change.

**Tech Stack:** Java 21, Maven wrapper, JUnit 6, bash, `gh`.

**Spec:** `docs/superpowers/specs/2026-11-24-tutorial-design.md`

**Scope:** This plan is step 1 of section 9 of the spec. Plan 2 adds the four optional lessons and the publication, after the run of Task 6. The publication includes the link to the tutorial in the README of `rossumworks`, as section 3 of the spec tells.

**Locations:** Tasks 1 to 4 work in the new folder `rossumworks-tutorial`, next to the clone of `rossumworks` (here: `SAMPLE`). Task 5 works in the worktree `.worktree/tutorial-design` of `rossumworks` (here: `FACTORY`). Run each command in the root of the repository of its task.

**Rules:** All texts use Simplified Technical English. All names are complete words. Commit messages end with the attribution lines of the session.

**Evidence:** The code of Tasks 1 to 3 ran once in a scratch folder. The build passed, the ten script tests passed, and `shellcheck` gave no finding.

---

### Task 1: The project and the cart

**Files:**
- Create: `SAMPLE/pom.xml`
- Create: `SAMPLE/.gitignore`
- Create: `SAMPLE/LICENSE`
- Create: `SAMPLE/mvnw`
- Create: `SAMPLE/mvnw.cmd`
- Create: `SAMPLE/.mvn/wrapper/maven-wrapper.properties`
- Create: `SAMPLE/src/main/java/com/meterian/checkout/CartLine.java`
- Create: `SAMPLE/src/main/java/com/meterian/checkout/Cart.java`
- Test: `SAMPLE/src/test/java/com/meterian/checkout/CartLineTest.java`

- [ ] **Step 1: Make the repository, and copy the wrapper and the license**

```bash
FACTORY=<the clone of rossumworks>
SAMPLE=<the parent folder of FACTORY>/rossumworks-tutorial
git init -b main "$SAMPLE" && cd "$SAMPLE"
mkdir -p .mvn/wrapper src/main/java/com/meterian/checkout src/test/java/com/meterian/checkout
cp "$FACTORY"/mvnw "$FACTORY"/mvnw.cmd "$FACTORY"/LICENSE .
cp "$FACTORY"/.mvn/wrapper/maven-wrapper.properties .mvn/wrapper/
chmod +x mvnw
```

Expected: no error.

- [ ] **Step 2: Write the build files**

`pom.xml`:

````xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.meterian</groupId>
    <artifactId>checkout</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>RossumWorks tutorial</name>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>6.1.3</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.16.0</version>
            </plugin>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.6.0</version>
            </plugin>
        </plugins>
    </build>
</project>
````

`.gitignore`:

````
target/
````

- [ ] **Step 3: Write the failing test**

`src/test/java/com/meterian/checkout/CartLineTest.java`:

````java
package com.meterian.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CartLineTest {

    @Test
    void multipliesTheUnitPriceByTheQuantity() {
        assertEquals(new BigDecimal("6.00"), new CartLine("pen", new BigDecimal("1.50"), 4).calculatePrice());
    }

    @Test
    void refusesAQuantityOfZero() {
        assertThrows(IllegalArgumentException.class, () -> new CartLine("pen", BigDecimal.ONE, 0));
    }

    @Test
    void refusesANegativePrice() {
        assertThrows(IllegalArgumentException.class, () -> new CartLine("pen", new BigDecimal("-1"), 1));
    }

    @Test
    void refusesAPriceWithThreeDecimals() {
        assertThrows(IllegalArgumentException.class, () -> new CartLine("pen", new BigDecimal("1.005"), 1));
    }
}
````

- [ ] **Step 4: Run the test**

```bash
./mvnw -q verify
```

Expected: FAIL, the compiler cannot find `CartLine`.

- [ ] **Step 5: Write the classes**

`src/main/java/com/meterian/checkout/CartLine.java`:

````java
package com.meterian.checkout;

import java.math.BigDecimal;

public record CartLine(String product, BigDecimal unitPrice, int quantity) {

    public CartLine {
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("The unit price must not be negative.");
        }
        if (unitPrice.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("The unit price must have 2 decimals or less.");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("The quantity must be 1 or more.");
        }
    }

    public BigDecimal calculatePrice() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
````

`src/main/java/com/meterian/checkout/Cart.java`:

````java
package com.meterian.checkout;

import java.math.BigDecimal;
import java.util.List;

public record Cart(List<CartLine> lines) {

    public Cart {
        lines = List.copyOf(lines);
    }

    public BigDecimal calculateSubtotal() {
        return lines.stream().map(CartLine::calculatePrice).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
````

- [ ] **Step 6: Run the test again**

```bash
./mvnw -q verify
```

Expected: exit code 0.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "Add the cart of the checkout"
```

Expected: one new commit.

### Task 2: The price, the payment, and the command

**Files:**
- Create: `SAMPLE/src/main/java/com/meterian/checkout/Discount.java`
- Create: `SAMPLE/src/main/java/com/meterian/checkout/PriceCalculator.java`
- Create: `SAMPLE/src/main/java/com/meterian/checkout/PaymentRule.java`
- Create: `SAMPLE/src/main/java/com/meterian/checkout/CheckoutCommand.java`
- Test: `SAMPLE/src/test/java/com/meterian/checkout/PriceCalculatorTest.java`
- Test: `SAMPLE/src/test/java/com/meterian/checkout/PaymentRuleTest.java`
- Test: `SAMPLE/src/test/java/com/meterian/checkout/CheckoutCommandTest.java`

`RoundingMode.DOWN` in `PriceCalculator` is the planned bug. Do not correct it.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/meterian/checkout/PriceCalculatorTest.java`:

````java
package com.meterian.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceCalculatorTest {

    private final PriceCalculator priceCalculator = new PriceCalculator();

    @Test
    void addsThePricesOfAllLines() {
        Cart cart = new Cart(List.of(
                new CartLine("book", new BigDecimal("10.00"), 2),
                new CartLine("pen", new BigDecimal("1.50"), 1)));

        assertEquals(new BigDecimal("21.50"), priceCalculator.calculateTotal(cart, Discount.NONE));
    }

    @Test
    void subtractsTheDiscount() {
        Cart cart = new Cart(List.of(new CartLine("book", new BigDecimal("20.00"), 1)));

        assertEquals(new BigDecimal("18.00"), priceCalculator.calculateTotal(cart, new Discount(10)));
    }
}
````

`src/test/java/com/meterian/checkout/PaymentRuleTest.java`:

````java
package com.meterian.checkout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentRuleTest {

    private final PaymentRule paymentRule = new PaymentRule();

    @Test
    void acceptsTheHighestAmount() {
        assertTrue(paymentRule.accepts(new BigDecimal("1000.00")));
    }

    @Test
    void refusesAHigherAmount() {
        assertFalse(paymentRule.accepts(new BigDecimal("1000.01")));
    }

    @Test
    void refusesZero() {
        assertFalse(paymentRule.accepts(new BigDecimal("0.00")));
    }
}
````

`src/test/java/com/meterian/checkout/CheckoutCommandTest.java`:

````java
package com.meterian.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CheckoutCommandTest {

    private final CheckoutCommand checkoutCommand = new CheckoutCommand();

    @Test
    void showsTheTotalAndThePayment() {
        assertEquals("Total: 18.00" + System.lineSeparator() + "Payment: accepted",
                checkoutCommand.describeCheckout(List.of("book:10.00:2", "--discount=10")));
    }

    @Test
    void showsARefusedPayment() {
        assertEquals("Total: 2000.00" + System.lineSeparator() + "Payment: refused",
                checkoutCommand.describeCheckout(List.of("laptop:2000.00:1")));
    }

    @Test
    void refusesAnEmptyCart() {
        assertThrows(IllegalArgumentException.class, () -> checkoutCommand.describeCheckout(List.of()));
    }

    @Test
    void refusesALineThatIsNotCorrect() {
        assertThrows(IllegalArgumentException.class, () -> checkoutCommand.describeCheckout(List.of("book:ten:2")));
    }

    @Test
    void refusesADiscountThatIsNotANumber() {
        assertThrows(IllegalArgumentException.class,
                () -> checkoutCommand.describeCheckout(List.of("book:10.00:2", "--discount=ten")));
    }
}
````

- [ ] **Step 2: Run the tests**

```bash
./mvnw -q verify
```

Expected: FAIL, the compiler cannot find `PriceCalculator`.

- [ ] **Step 3: Write the classes**

`src/main/java/com/meterian/checkout/Discount.java`:

````java
package com.meterian.checkout;

import java.math.BigDecimal;

/** A discount in percent. The amount is exact, without rounding. */
public record Discount(int percent) {

    public static final Discount NONE = new Discount(0);

    public Discount {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("The discount must be from 0 to 100 percent.");
        }
    }

    public BigDecimal calculateAmount(BigDecimal price) {
        return price.multiply(BigDecimal.valueOf(percent)).movePointLeft(2);
    }
}
````

`src/main/java/com/meterian/checkout/PriceCalculator.java`:

````java
package com.meterian.checkout;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PriceCalculator {

    private static final int DECIMALS = 2;

    public BigDecimal calculateTotal(Cart cart, Discount discount) {
        BigDecimal subtotal = cart.calculateSubtotal();
        BigDecimal discountAmount = discount.calculateAmount(subtotal).setScale(DECIMALS, RoundingMode.DOWN);
        return subtotal.subtract(discountAmount).setScale(DECIMALS);
    }
}
````

`src/main/java/com/meterian/checkout/PaymentRule.java`:

````java
package com.meterian.checkout;

import java.math.BigDecimal;

public final class PaymentRule {

    private static final BigDecimal HIGHEST_ACCEPTED_AMOUNT = new BigDecimal("1000.00");

    public boolean accepts(BigDecimal amount) {
        return amount.signum() > 0 && amount.compareTo(HIGHEST_ACCEPTED_AMOUNT) <= 0;
    }
}
````

`src/main/java/com/meterian/checkout/CheckoutCommand.java`:

````java
package com.meterian.checkout;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class CheckoutCommand {

    static final String USAGE = "Usage: <product>:<unit price>:<quantity>... [--discount=<percent>]";

    private static final String DISCOUNT_OPTION = "--discount=";
    private static final int PARTS_OF_A_LINE = 3;

    private final PriceCalculator priceCalculator = new PriceCalculator();
    private final PaymentRule paymentRule = new PaymentRule();

    public static void main(String[] arguments) {
        try {
            System.out.println(new CheckoutCommand().describeCheckout(List.of(arguments)));
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println(USAGE);
            System.exit(1);
        }
    }

    String describeCheckout(List<String> arguments) {
        BigDecimal total = priceCalculator.calculateTotal(readCart(arguments), readDiscount(arguments));
        String payment = paymentRule.accepts(total) ? "accepted" : "refused";
        return "Total: " + total + System.lineSeparator() + "Payment: " + payment;
    }

    private static Cart readCart(List<String> arguments) {
        List<CartLine> lines = new ArrayList<>();
        for (String argument : arguments) {
            if (!argument.startsWith(DISCOUNT_OPTION)) {
                lines.add(readLine(argument));
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("The cart is empty.");
        }
        return new Cart(lines);
    }

    private static CartLine readLine(String argument) {
        String[] parts = argument.split(":");
        if (parts.length != PARTS_OF_A_LINE) {
            throw new IllegalArgumentException("The cart line is not correct: " + argument);
        }
        try {
            return new CartLine(parts[0], new BigDecimal(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("The cart line is not correct: " + argument, exception);
        }
    }

    private static Discount readDiscount(List<String> arguments) {
        return arguments.stream()
                .filter(argument -> argument.startsWith(DISCOUNT_OPTION))
                .map(argument -> argument.substring(DISCOUNT_OPTION.length()))
                .map(CheckoutCommand::parseDiscount)
                .findFirst()
                .orElse(Discount.NONE);
    }

    private static Discount parseDiscount(String percent) {
        try {
            return new Discount(Integer.parseInt(percent));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("The discount is not a whole number: " + percent, exception);
        }
    }
}
````

- [ ] **Step 4: Run the tests again**

```bash
time ./mvnw -q verify
```

Expected: exit code 0, in less than 30 seconds.

- [ ] **Step 5: Prove the planned bug**

Add this test to `PriceCalculatorTest`, for this step only:

```java
    @Test
    void roundsHalfACentOfTheDiscountUp() {
        Cart cart = new Cart(List.of(new CartLine("book", new BigDecimal("19.95"), 1)));

        assertEquals(new BigDecimal("17.95"), priceCalculator.calculateTotal(cart, new Discount(10)));
    }
```

```bash
./mvnw -q verify
```

Expected: FAIL, `expected: <17.95> but was: <17.96>`.

Remove the test.

- [ ] **Step 6: Run the program**

```bash
./mvnw -q verify
java -cp target/classes com.meterian.checkout.CheckoutCommand book:19.95:1 --discount=10
```

Expected: `Total: 17.96` and `Payment: accepted`.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "Add the price, the payment, and the command"
```

Expected: one new commit.

### Task 3: The issue script

**Files:**
- Create: `SAMPLE/tutorial/create-issues.sh`
- Create: `SAMPLE/tutorial/issues/first-fix/round-the-discount.md`
- Test: `SAMPLE/tutorial/test/test-create-issues.sh`

- [ ] **Step 1: Write the failing test**

`tutorial/test/test-create-issues.sh`:

````bash
#!/usr/bin/env bash
# Tests create-issues.sh with a fake gh: tutorial/test/test-create-issues.sh
set -euo pipefail

TUTORIAL_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKSPACE=$(mktemp -d)
readonly TUTORIAL_DIRECTORY WORKSPACE
trap 'rm -rf "${WORKSPACE}"' EXIT

# The fake keeps the created titles in a file, and fails on the create with the number FAIL_AT.
write_fake_gh() {
    mkdir -p "${WORKSPACE}/bin"
    cat > "${WORKSPACE}/bin/gh" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "${GH_CALLS}"
case "$1 $2" in
    "auth status") exit "${GH_AUTH_EXIT:-0}" ;;
    "issue list") cat "${GH_TITLES}" ;;
    "issue create")
        if [[ "$(grep -c '^issue create' "${GH_CALLS}")" == "${FAIL_AT:-none}" ]]; then
            exit 1
        fi
        echo "$6" >> "${GH_TITLES}"
        echo "https://github.com/$4/issues/1"
        ;;
esac
FAKE
    chmod +x "${WORKSPACE}/bin/gh"
}

prepare_clone() {
    rm -rf "${WORKSPACE}/clone"
    mkdir -p "${WORKSPACE}/clone/tutorial/issues/sample"
    cp "${TUTORIAL_DIRECTORY}/create-issues.sh" "${WORKSPACE}/clone/tutorial/"
    printf 'First title\n\nFirst body\n' > "${WORKSPACE}/clone/tutorial/issues/sample/first.md"
    printf 'Second title\n\nSecond body\n' > "${WORKSPACE}/clone/tutorial/issues/sample/second.md"
    git -C "${WORKSPACE}/clone" init --quiet
    git -C "${WORKSPACE}/clone" remote add origin "$1"
    export GH_CALLS="${WORKSPACE}/calls" GH_TITLES="${WORKSPACE}/titles"
    : > "${GH_CALLS}"
    : > "${GH_TITLES}"
}

# Runs the script with the answer $1 and the other arguments. Sets output and exit_code.
run_script() {
    local answer="$1"
    shift
    exit_code=0
    output=$(PATH="${WORKSPACE}/bin:${PATH}" "${WORKSPACE}/clone/tutorial/create-issues.sh" "$@" \
        <<< "${answer}" 2>&1) || exit_code=$?
}

count_created() {
    wc -l < "${GH_TITLES}" | tr -d ' '
}

assert_equals() {
    if [[ "$1" != "$2" ]]; then
        echo "FAIL: ${FUNCNAME[1]}: expected [$1], got [$2]" >&2
        echo "${output}" >&2
        exit 1
    fi
}

assert_output_has() {
    if [[ "${output}" != *"$1"* ]]; then
        echo "FAIL: ${FUNCNAME[1]}: the output does not have [$1]" >&2
        echo "${output}" >&2
        exit 1
    fi
}

test_first_run_creates_all_issues() {
    prepare_clone git@github.com:someone/sample.git
    run_script y sample
    assert_equals 0 "${exit_code}"
    assert_equals 2 "$(count_created)"
    assert_equals "issue create --repo someone/sample --title First title --body First body" \
        "$(grep '^issue create' "${GH_CALLS}" | head -n 1)"
}

test_second_run_creates_nothing() {
    prepare_clone https://github.com/someone/sample.git
    run_script y sample
    run_script y sample
    assert_equals 0 "${exit_code}"
    assert_equals 2 "$(count_created)"
    assert_output_has "All the issues of the lesson sample exist"
}

test_similar_title_does_not_count() {
    prepare_clone https://github.com/someone/sample
    echo "First title again" > "${GH_TITLES}"
    run_script y sample
    assert_equals 3 "$(count_created)"
}

test_refused_confirmation_creates_nothing() {
    prepare_clone https://github.com/someone/sample
    run_script n sample
    assert_equals 0 "${exit_code}"
    assert_equals 0 "$(count_created)"
}

test_template_repository_is_refused() {
    prepare_clone git@github.com:MeterianHQ/rossumworks-tutorial.git
    run_script y sample
    assert_equals 1 "${exit_code}"
    assert_equals 0 "$(count_created)"
    assert_output_has "template repository"
}

test_other_host_is_refused() {
    prepare_clone git@example.com:someone/sample.git
    run_script y sample
    assert_equals 1 "${exit_code}"
    assert_equals 0 "$(count_created)"
}

test_unknown_lesson_shows_lessons() {
    prepare_clone https://github.com/someone/sample
    run_script y other
    assert_equals 1 "${exit_code}"
    assert_output_has "  sample"
}

test_no_lesson_shows_lessons() {
    prepare_clone https://github.com/someone/sample
    run_script y
    assert_equals 1 "${exit_code}"
    assert_output_has "  sample"
}

test_no_login_stops() {
    prepare_clone https://github.com/someone/sample
    GH_AUTH_EXIT=1 run_script y sample
    assert_equals 1 "${exit_code}"
    assert_equals 0 "$(count_created)"
    assert_output_has "gh auth login"
}

test_run_after_failure_creates_missing_issue() {
    prepare_clone https://github.com/someone/sample
    FAIL_AT=2 run_script y sample
    assert_equals 1 "${exit_code}"
    assert_equals 1 "$(count_created)"
    run_script y sample
    assert_equals 0 "${exit_code}"
    assert_equals 2 "$(count_created)"
}

write_fake_gh
for test_name in $(compgen -A function test_); do
    "${test_name}"
    echo "ok ${test_name}"
done
````

- [ ] **Step 2: Run the test**

```bash
chmod +x tutorial/test/test-create-issues.sh && tutorial/test/test-create-issues.sh
```

Expected: FAIL, `cp` cannot find `create-issues.sh`.

- [ ] **Step 3: Write the script and the issue**

`tutorial/create-issues.sh`:

````bash
#!/usr/bin/env bash
# Creates the issues of one lesson of the tutorial: tutorial/create-issues.sh <lesson>
set -euo pipefail

readonly TEMPLATE_REPOSITORY="MeterianHQ/rossumworks-tutorial"
ISSUES_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/issues"
readonly ISSUES_DIRECTORY

stop_with_message() {
    echo "$1" >&2
    exit 1
}

show_lessons() {
    echo "Usage: tutorial/create-issues.sh <lesson>"
    echo "The lessons are:"
    find "${ISSUES_DIRECTORY}" -mindepth 1 -maxdepth 1 -type d -printf '  %f\n' | sort
}

verify_lesson_exists() {
    if [[ ! "$1" =~ ^[a-z-]+$ || ! -d "${ISSUES_DIRECTORY}/$1" ]]; then
        show_lessons >&2
        exit 1
    fi
}

verify_programs() {
    local program
    for program in gh git; do
        command -v "${program}" > /dev/null || stop_with_message "The script needs ${program} on the PATH."
    done
    gh auth status > /dev/null 2>&1 || stop_with_message "Log in with: gh auth login"
}

find_repository() {
    local url
    url=$(git -C "${ISSUES_DIRECTORY}" remote get-url origin) \
        || stop_with_message "The clone has no remote with the name origin."
    if [[ ! "${url}" =~ github\.com[:/]([^/]+/[^/]+)$ ]]; then
        stop_with_message "The remote origin is not a GitHub repository: ${url}"
    fi
    echo "${BASH_REMATCH[1]%.git}"
}

verify_repository_is_not_template() {
    if [[ "${1,,}" == "${TEMPLATE_REPOSITORY,,}" ]]; then
        stop_with_message "This is the template repository. Make your own repository with \"Use this template\"."
    fi
}

read_title() {
    head -n 1 "$1"
}

read_body() {
    tail -n +3 "$1"
}

find_new_issue_files() {
    local lesson="$1"
    local existing_titles="$2"
    local file
    for file in "${ISSUES_DIRECTORY}/${lesson}"/*.md; do
        if ! grep --quiet --fixed-strings --line-regexp -- "$(read_title "${file}")" <<< "${existing_titles}"; then
            echo "${file}"
        fi
    done
}

confirm_creation() {
    local repository="$1"
    shift
    local file answer=""
    echo "The script creates these issues in ${repository}:"
    for file in "$@"; do
        echo "  $(read_title "${file}")"
    done
    read -r -p "Create the issues? [y/n] " answer || true
    [[ "${answer}" == "y" ]]
}

create_issues() {
    local repository="$1"
    shift
    local file
    for file in "$@"; do
        gh issue create --repo "${repository}" --title "$(read_title "${file}")" --body "$(read_body "${file}")"
    done
}

main() {
    local lesson="${1:-}"
    local repository existing_titles new_files
    verify_lesson_exists "${lesson}"
    verify_programs
    repository=$(find_repository)
    verify_repository_is_not_template "${repository}"
    existing_titles=$(gh issue list --repo "${repository}" --state all --limit 1000 --json title --jq '.[].title')
    mapfile -t new_files < <(find_new_issue_files "${lesson}" "${existing_titles}")
    if [[ ${#new_files[@]} -eq 0 ]]; then
        echo "All the issues of the lesson ${lesson} exist in ${repository}."
        return
    fi
    if ! confirm_creation "${repository}" "${new_files[@]}"; then
        echo "The script created no issue."
        return
    fi
    create_issues "${repository}" "${new_files[@]}"
    echo "Add the label factory to an issue to give it to the factory."
}

main "$@"
````

`tutorial/issues/first-fix/round-the-discount.md`:

````markdown
Round the discount to the nearest cent

The program always rounds the discount down. The discount must round to the nearest cent. Half a cent rounds up.

An example:

- The cart has one book for 19.95. The discount is 10 percent.
- The exact discount is 1.995. The rounded discount is 2.00.
- The correct total is 17.95. The program shows 17.96.

Add a test with this example.
````

- [ ] **Step 4: Run the test again**

```bash
chmod +x tutorial/create-issues.sh && tutorial/test/test-create-issues.sh
```

Expected: ten lines that start with `ok`.

- [ ] **Step 5: Run shellcheck**

```bash
docker run --rm -v "$PWD:/mnt:ro" koalaman/shellcheck:stable tutorial/create-issues.sh tutorial/test/test-create-issues.sh
```

Expected: no finding.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add the script that creates the issues of a lesson"
```

Expected: one new commit.

### Task 4: The documents of the sample

**Files:**
- Create: `SAMPLE/README.md`
- Create: `SAMPLE/CLAUDE.md`

- [ ] **Step 1: Write the documents**

`README.md`:

````markdown
# RossumWorks tutorial

This is the sample project of the [RossumWorks](https://github.com/MeterianHQ/rossumworks) tutorial.
It is a small shop checkout. You give its issues to the factory.

Read the [tutorial](https://github.com/MeterianHQ/rossumworks/blob/main/docs/tutorial.md) for the steps.
````

`CLAUDE.md`:

````markdown
# Claude Context — Checkout

## Build

- Build and test with `./mvnw verify`.
- The build needs a JDK 21 or later.

## Code

- Give each class and each method one responsibility.
- Start the name of a method with a verb, for example `calculateTotal`.
- Do not use truncated names. Write `quantity`, not `qty`.
- Use `BigDecimal` for money. Do not use `double` or `float`.
- Write a test for each change of behavior.

## Writing style

Write code comments and documents in Simplified Technical English (ASD-STE100).
Write short sentences, and use the active voice and the simple tenses.
````

- [ ] **Step 2: Check the language**

Read each text of the sample against section 6.5 of the spec. Correct each sentence that breaks a rule.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "Add the documents of the sample"
```

Expected: one new commit.

### Task 5: The core of the tutorial

**Files:**
- Create: `FACTORY/docs/tutorial.md`

- [ ] **Step 1: Write the tutorial**

`docs/tutorial.md`:

````markdown
# Tutorial

In this tutorial, you give a small project to RossumWorks. The factory fixes a bug, reviews the fix, and merges it.
[Getting started](getting-started.md) tells how you use the factory on your own project. The
[Glossary](glossary.md) gives the meaning of the terms.

## Before you start

**Warning: the agents run commands on your machine, with the rights of your user.**

**Warning: each issue uses your subscription, or costs money with an API key.** An issue needs the session of the
agent, four reviews, and a risk check. A review that rejects the change causes more sessions.

**Warning: the Anthropic documents do not agree about the use of a subscription from a script.** Read
[Legal considerations](legal-considerations.md) before you select a provider.

You need:

- Linux.
- A JDK 21 or later.
- The `claude` CLI.
- A provider. The provider gives the `claude` sessions their access to Claude, and pays for them. See
  [Providers](providers.md). The default is your Claude subscription, with a `claude` login.
- The `gh` CLI. Run `gh auth login` to log in.
- `git`, `setsid`, `kill`, `tmux`, `jq`, and `curl` on the `PATH`.

For the subscription, do not set `ANTHROPIC_API_KEY`. For an API key, set `ANTHROPIC_API_KEY` to the key.

## Make your repository

1. Open the [template repository](https://github.com/MeterianHQ/rossumworks-tutorial).
2. Select "Use this template", and then "Create a new repository".
3. Select your account, type a name, and select "Private".
4. Select "Create repository".
5. Clone your repository with `gh repo clone <owner>/<name>`.
6. Go to the folder of the clone.
7. Run `./mvnw verify`. The build must pass.
8. Run this command to see the bug:

   ```
   java -cp target/classes com.meterian.checkout.CheckoutCommand book:19.95:1 --discount=10
   ```

The program shows `Total: 17.96`. The correct total is 17.95.

## Set up the factory

The factory reads its workflow from the folder `.factory` of your repository. The command `create` makes this folder
in a pull request (PR).

1. Download `rossumworks-<version>.jar` from the
   [releases](https://github.com/MeterianHQ/rossumworks/releases).
2. Run `java -jar <path of the jar> create` in the clone.
3. Push Enter at each question to take the default answer. For an API key, type `anthropic-api-key` at the question
   about the provider, and then type a daily budget.
4. Read the summary, and answer `y`.
5. Open the PR `factory/setup` on GitHub, and merge it.
6. Run `git pull` in the clone.
7. Run `.factory/run.sh`. The worker is the program that does the work of the factory. Do not close this terminal.
8. Open <http://127.0.0.1:8090/>. This page is the board. It shows one card for each issue of the factory.

An empty password gives a page without a login. Each program on your machine can then use the page.

## Give an issue to the factory

1. Open a second terminal, and go to the folder of the clone.
2. Run `tutorial/create-issues.sh first-fix`.
3. Read the repository and the title, and answer `y`.
4. Open the issue on GitHub, and read it.
5. Add the label `factory` to the issue.

The worker checks GitHub each 60 seconds. Then the card comes on the board.

## Watch the board

| Column | What the factory does |
|---|---|
| Queued | The issue waits for a free slot. |
| In progress | The agent changes the code. Then the worker runs the build. |
| In review | Four reviews check the change. Then the risk check gives the risk of the change. |
| Waits for you | The factory needs your decision. |
| Blocked | The factory cannot continue. |
| Done | The change is in the default branch. |

A card can go back from "In review" to "In progress". Then a review found a problem, and the agent corrects it.

The risk decides the merge:

- The risk is low and the change is small: the factory merges the PR.
- Else, the card goes to "Waits for you". Read the PR, and then push "Approve" on the card.

When the card is in "Done", do these steps:

1. Run `git pull` in the clone.
2. Run `./mvnw verify`.
3. Run the `java -cp` command again. The program shows `Total: 17.95`.
4. Open the PR on GitHub. Read the description and the reviews.

## Problems

| Problem | Solution |
|---|---|
| The message tells you to remove `ANTHROPIC_API_KEY`. | To use the subscription, run `unset ANTHROPIC_API_KEY`. Then run the command again. |
| The message tells you to set `ANTHROPIC_API_KEY`. | Set the variable to your key. Then run the command again. |
| The message tells you that the factory needs commands on the `PATH`. | Install the commands that the message names. |
| No card comes on the board. | Make sure that you merged the PR `factory/setup`. Make sure that the issue has no assignee. Read the top of the board and the worker log. |
| The worker log has a warning about the assignee. | Use an account that can write to the repository. |
| The work waits for the usage window or for the daily budget. | Do not stop the worker. It continues when the limit permits it. See [Providers](providers.md). |
| The card is in "Blocked". | Read the note on the card. Push "Continue" to keep the work, or "Start again" to discard it. |

## Clean up

**Warning: a delete is final. Check the name before each delete.**

1. Push Ctrl-C in the terminal of the worker.
2. Delete your repository in its settings on GitHub.
3. Delete the folder of the clone.
4. Delete the work folder `~/.rossumworks/<owner>-<name>`. Use the owner and the name of your repository.
````

- [ ] **Step 2: Check the language**

Read the text against section 6.5 of the spec. Correct each sentence that breaks a rule.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "Add the core of the tutorial"
```

Expected: one new commit.

### Task 6: The template repository and the full run

**Files:**
- Modify: `FACTORY/docs/tutorial.md`

**Ask the owner before Step 1 and Step 3.** Step 1 creates a repository in the organization. Step 3 uses the subscription.

- [ ] **Step 1: Create the private template repository, in `SAMPLE`**

```bash
gh repo create MeterianHQ/rossumworks-tutorial --private --source . --push
gh repo edit MeterianHQ/rossumworks-tutorial --template
```

Expected: `gh repo view MeterianHQ/rossumworks-tutorial --json isTemplate` shows `true`.

- [ ] **Step 2: Check the guard of the script**

```bash
tutorial/create-issues.sh first-fix
```

Expected: exit code 1 and "This is the template repository."

- [ ] **Step 3: Follow the tutorial**

A person follows `docs/tutorial.md` from the start, in a new private repository from the template. Do not use other knowledge. Write down each step that fails or is not clear. Write down the usage percent of the board before the label and after "Done". Write down the time between them.

- [ ] **Step 4: Correct the tutorial**

Correct each step of the list. Add a section "Time and use" before "Problems". It gives the measured time and the measured usage percent of this run, the subscription plan, and the date. It tells that each run is different.

- [ ] **Step 5: Clean up**

The person does the section "Clean up" of the tutorial.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Correct the tutorial after the full run"
```

Expected: one new commit.

## Plan 2

Plan 2 follows the run of Task 6. It adds `live-mode` (after issue #48), `approval`, `factory-change` (the live test of issue #22), and `parallel`. Each lesson adds a folder in `tutorial/issues` and a section in `docs/tutorial.md`. The script finds a lesson from its folder, so it does not change. The last task is the publication.
