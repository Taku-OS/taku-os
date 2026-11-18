package org.agentos.assistant;

import org.json.JSONObject;
import java.util.Locale;

/** UI aliases only; native identifiers, descriptions and provenance remain authoritative. */
final class SkillPresentation {
    private SkillPresentation() {}

    static String title(String name) {
        switch (name) {
            case "aspnet-core": return "ASP.NET Core";
            case "chatgpt-apps": return "ChatGPT apps";
            case "cli-creator": return "CLI development";
            case "cloudflare-deploy": return "Cloudflare deployment";
            case "gh-fix-ci": return "CI troubleshooting";
            case "gh-address-comments": return "Address code reviews";
            case "jupyter-notebook": return "Jupyter notebooks";
            case "linear": return "Linear project management";
            case "netlify-deploy": return "Netlify deployment";
            case "render-deploy": return "Render deployment";
            case "vercel-deploy": return "Vercel deployment";
            case "screenshot": return "Screenshots";
            case "speech": return "Speech synthesis";
            case "transcribe": return "Speech transcription";
            case "winui-app": return "Windows apps";
            case "taku-app-development": return "App development";
            case "security-threat-model": return "Threat modeling";
            case "security-best-practices": return "Secure development guide";
            case "skill-creator": return "Create skills";
            case "skill-installer": return "Install skill";
            case "imagegen": return "Image creation";
            case "openai-docs": return "Developer documentation";
            case "frontend-design": return "Frontend design";
            case "pdf": return "PDF documents";
            case "slides": return "Presentations";
            case "spreadsheet": return "Spreadsheets";
            default: return name;
        }
    }

    static String category(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.matches(".*(image|design|figma|video|canvas|art|frontend).*")) return "Creative";
        if (value.matches(".*(develop|code|security|test|git|gh-|cli-|aspnet|winui|deploy|skill-|api|web|app|debug).*")) return "Development";
        return "Productivity";
    }

    static String description(JSONObject skill) {
        String description = skill.optString("description");
        return description.isEmpty() ? skill.optString("repo", "Public community") + " · Tap for skill instructions" : description;
    }

    static String summary(JSONObject skill) {
        switch (skill.optString("name")) {
            case "taku-app-development": return "Build lightweight apps or native APKs on the phone and verify actual installation and execution.";
            case "security-threat-model": return "Identify assets, trust boundaries, and security threats in code.";
            case "security-best-practices": return "Review common security issues in code and suggest improvements.";
            case "gh-fix-ci": return "Investigate failed GitHub Actions checks, locate logs, and plan fixes.";
            case "gh-address-comments": return "Organize GitHub code-review comments and address feedback item by item.";
            case "aspnet-core": return "Build, debug, and maintain ASP.NET Core web applications.";
            case "chatgpt-apps": return "Develop ChatGPT apps connecting tools and interactive interfaces.";
            case "cli-creator": return "Design, implement, and verify command-line tools.";
            case "cloudflare-deploy": return "Deploy apps to Cloudflare Workers, Pages, and related services.";
            case "imagegen": return "Generate or edit images to turn visual requirements into actual images.";
            case "openai-docs": return "Consult official developer documentation to verify interfaces and usage.";
            case "speech": return "Convert text to speech.";
            case "transcribe": return "Transcribe audio into text.";
            case "pdf": return "Read, create, and inspect PDF documents.";
            default:
                String copy = description(skill);
                int separator = copy.indexOf(';');
                return separator > 0 ? copy.substring(0, separator) + "." : copy;
        }
    }

    static boolean matches(JSONObject skill, String query, String filter) {
        String name = skill.optString("name");
        if (!filter.equals("All") && !category(name).equals(filter)) return false;
        return (title(name) + " " + name + " " + description(skill) + " " + summary(skill)).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }
}
