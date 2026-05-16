using System.Text;
using System.Text.Json;

namespace Neo4j.AgentMemory.Transport;

/// <summary>
/// snake_case ↔ camelCase translation for wire payloads.
/// Used by RestTransport to bridge bridge-style snake_case calls and the
/// hosted service's camelCase API.
/// </summary>
internal static class Casing
{
    public static string ToCamel(string snake)
    {
        if (string.IsNullOrEmpty(snake)) return snake;
        var sb = new StringBuilder();
        var upperNext = false;
        foreach (var c in snake)
        {
            if (c == '_')
            {
                upperNext = true;
                continue;
            }
            sb.Append(upperNext ? char.ToUpperInvariant(c) : c);
            upperNext = false;
        }
        return sb.ToString();
    }

    public static string ToSnake(string camel)
    {
        if (string.IsNullOrEmpty(camel)) return camel;
        var sb = new StringBuilder();
        for (var i = 0; i < camel.Length; i++)
        {
            var c = camel[i];
            if (i > 0 && char.IsUpper(c))
                sb.Append('_');
            sb.Append(char.ToLowerInvariant(c));
        }
        return sb.ToString();
    }

    /// <summary>Recursively rewrite map keys from snake_case to camelCase.</summary>
    public static object? SnakeToCamel(object? value)
    {
        return Rewrite(value, ToCamel);
    }

    /// <summary>Recursively rewrite map keys from camelCase to snake_case.</summary>
    public static object? CamelToSnake(object? value)
    {
        return Rewrite(value, ToSnake);
    }

    private static object? Rewrite(object? value, Func<string, string> rewrite)
    {
        switch (value)
        {
            case Dictionary<string, object?> dict:
            {
                var res = new Dictionary<string, object?>(dict.Count);
                foreach (var kv in dict)
                    res[rewrite(kv.Key)] = Rewrite(kv.Value, rewrite);
                return res;
            }
            case IDictionary<string, object?> idict:
            {
                var res = new Dictionary<string, object?>();
                foreach (var kv in idict)
                    res[rewrite(kv.Key)] = Rewrite(kv.Value, rewrite);
                return res;
            }
            case List<object?> list:
            {
                var res = new List<object?>(list.Count);
                foreach (var item in list)
                    res.Add(Rewrite(item, rewrite));
                return res;
            }
            case JsonElement el:
                return Rewrite(JsonElementToObject(el), rewrite);
            default:
                return value;
        }
    }

    public static object? JsonElementToObject(JsonElement el)
    {
        switch (el.ValueKind)
        {
            case JsonValueKind.Object:
            {
                var dict = new Dictionary<string, object?>();
                foreach (var p in el.EnumerateObject())
                    dict[p.Name] = JsonElementToObject(p.Value);
                return dict;
            }
            case JsonValueKind.Array:
            {
                var list = new List<object?>();
                foreach (var item in el.EnumerateArray())
                    list.Add(JsonElementToObject(item));
                return list;
            }
            case JsonValueKind.String: return el.GetString();
            case JsonValueKind.Number:
                if (el.TryGetInt64(out var l)) return l;
                if (el.TryGetDouble(out var d)) return d;
                return el.GetRawText();
            case JsonValueKind.True: return true;
            case JsonValueKind.False: return false;
            case JsonValueKind.Null:
            case JsonValueKind.Undefined:
            default:
                return null;
        }
    }
}
