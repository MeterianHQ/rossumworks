# Legal considerations

This document is not legal advice. It records what the Anthropic documents say about the use of a Claude
subscription in an automated factory. A person checked the documents on 2026-11-23. Anthropic can change
them at any time, so read the current versions before you decide.

## 1. What the factory does

- The factory runs the unmodified `claude` CLI.
- In the current version, each session uses the Claude subscription of the owner.
- The factory never uses the Claude Agent SDK, and it never calls an Anthropic endpoint directly.
- A Java worker starts the sessions with `claude -p`, without a human. Thus the factory is a script.

## 2. Statements against the use of a subscription

**Consumer Terms, section 3, item 7.** The Consumer Terms (effective Oct 8, 2025) apply to the Free, Pro,
and Max plans. Item 7 starts with an exception: "Except when you are accessing our Services via an Anthropic
API Key or where we otherwise explicitly permit it". Item 7 then forbids access "through automated or
non-human means, whether through a bot, script, or otherwise". The factory starts sessions with a script.

**Claude Code legal page.** OAuth authentication "is designed to support ordinary use of Claude Code and
other native Anthropic applications". Also: "Advertised usage limits for Pro, Max, and Ultra plans assume
ordinary, individual usage of Claude Code and the Agent SDK." A factory that runs sessions all day can be
outside "ordinary" use.

**Consumer Terms, section 11.** Under the heading "Non-commercial use only", the text says: "You agree not
to use our Services for any commercial or business purposes". The same page also has a sentence for
consumers who live in the United Kingdom. It is not clear to which users this clause applies. Read the
section yourself if you use the factory for the work of a company.

**Consumer Terms, note on organizations.** If you act on behalf of an organization, the Commercial Terms of
Service govern your use of an Anthropic API key.

## 3. Statements for the use of a subscription

**Claude Code legal page.** The restrictions on third-party developers do not "prevent an end user from
signing in to the unmodified Claude Code binary with their own Claude subscription". The factory does
this: the owner signs in to the unmodified binary with the subscription of the owner.

**Headless documentation.** Anthropic documents `claude -p` "for scripts and CI/CD" and for "a scheduled
job". Only the `--bare` mode needs an API key. You can say that this is the "explicitly permit" exception of
item 7. That conclusion is not certain, because the documentation does not say it directly.

**Claude Code legal page, usage limits.** The text includes "ordinary, individual usage of Claude Code and
the Agent SDK" in the subscription limits. Thus the subscription limits include some automated use.

## 4. The Agent SDK and third-party tools

The legal page tells developers who build products with the Agent SDK to use an API key. Anthropic does not
permit third-party developers to offer Claude.ai login, or to send requests with the subscription
credentials of their users. The factory does not use the Agent SDK or a third-party harness.

In February 2026, The Register quoted a stricter sentence from the legal page. That sentence said that the
use of subscription OAuth tokens in "any other product, tool, or service", including the Agent SDK, is not
permitted. The current legal page does not contain that sentence.

## 5. Remote Control

Remote Control needs a claude.ai subscription. It does not work with an API key. This has an effect on the
live mode of the factory when it uses an API key.

## 6. Conclusion

- No Anthropic document says clearly that a personal script that runs `claude -p` with a subscription is
  permitted.
- No Anthropic document says clearly that the unmodified `claude` CLI in a script is forbidden.
- The factory has the strongest position that is possible with a subscription: the unmodified binary, the
  login of the owner, no API key, no Agent SDK, and no internal endpoints.
- Item 7 is still a risk. An API key removes this risk, because item 7 has an exception for it.

To remove the risk:

1. Ask Anthropic support for a written confirmation of your use.
2. Or use an Anthropic API key. Set `claude.provider: anthropic-api-key` in `worker.yaml`. See the section
   "Provider" of the README. If you use the key for an organization, the Commercial Terms of Service apply.

## 7. Sources

- [Claude Code legal and compliance](https://code.claude.com/docs/en/legal-and-compliance)
- [Anthropic Consumer Terms](https://www.anthropic.com/legal/consumer-terms)
- [Claude Code headless mode](https://code.claude.com/docs/en/headless)
- [Agent SDK overview](https://code.claude.com/docs/en/agent-sdk/overview)
- [Claude Code Remote Control](https://code.claude.com/docs/en/remote-control)
- [Using Claude Code with your Pro or Max plan](https://support.claude.com/en/articles/11145838-using-claude-code-with-your-pro-or-max-plan)
- [The Register, 20 Feb 2026](https://www.theregister.com/software/2026/02/20/anthropic-clarifies-ban-on-third-party-tool-access-to-claude/5014546)
