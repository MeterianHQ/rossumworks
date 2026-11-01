# Claude Context — claude-ai-factory

## Build

- Build and test with `./mvnw verify`.
- The build needs a JDK 21 or later.
- The package root is `com.meterian.factory`. Do not add `module-info.java` files.

## Writing style: Simplified Technical English

Write all replies and all documents in Simplified Technical English
(ASD-STE100). This applies to chat replies, commit messages, code comments,
and Markdown documents.

- Write short sentences. A procedure sentence has 20 words or less. A
  descriptive sentence has 25 words or less.
- Use the active voice.
- Give one instruction in one sentence.
- Use the simple tenses. Do not use the perfect tenses or the continuous
  tenses.
- Use the same word for the same thing every time. Do not use synonyms.
- Use three nouns or less in a noun cluster.
- Write six sentences or less in a paragraph.
- Do not use slang, idioms, or figures of speech.
- Keep the articles "a" and "the".

Technical names do not change. Class names, module names, command names, and
file names stay as they are.

## Clean code

The code must be clean and human friendly. A person who opens a file must
read it like the page of a book. Nothing is obscure or truncated.

- Apply the single responsibility principle always: to each class and each
  method.
- Every name must make sense: classes, methods, fields, variables, and
  parameters.
- A method name defines an action: a verb, for example
  `verifyRepositoryIsClean`.
- Do not use truncated names, for example `ctx`, `repo`, `dir`, or `e`. Write
  `context`, `repository`, `directory`, and `exception`.

## Code comments

One line, and only when the code is complex. Otherwise, write code that
speaks: clear names and small methods.
Javadoc can have more than one line, for example a summary and `@param` lines.
