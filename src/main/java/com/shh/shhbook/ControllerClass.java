package com.shh.shhbook;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.shh.shhbook.model.Posts;
import com.shh.shhbook.repository.PostsRepository;
import com.shh.shhbook.model.Users;
import com.shh.shhbook.service.FtpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.apache.tomcat.util.http.fileupload.ByteArrayOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;

@Controller
public class ControllerClass<Map> {
    @Autowired
    private AmazonS3 amazonS3;

    @Value("${aws.s3.bucket.name}")
    private String bucketName;
    private final String LOCAL_FILE_PATH = "uploads/";
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PostsRepository postsRepository;
    // strona poczatkowa - logowanie
    @RequestMapping(value={"/", "/search"}, method = RequestMethod.GET)
    public String session(HttpServletRequest request, ModelMap model) {

        //  jezeli bylo wyszukiwanie, to wyswietlamy posty wyszukane
        if (request.getRequestURI().equals("/search"))
        {
            if (request.getMethod().equals("GET")) {
                List<Posts> postsList = postsRepository.findByDescriptionContaining(request.getParameter("search_field"));
                Collections.reverse(postsList);
                model.addAttribute("db_posts", postsList);
            }
        }

        // w przeciwnym wypadku (strona glowna) wyswietlamy wszystkie
        else {
            List<Posts> postsList = postsRepository.findAll();
            Collections.reverse(postsList);
            model.addAttribute("db_posts", postsList);
        }

        HttpSession session = request.getSession(false);
        if (session != null) {
            model.addAttribute("user", session.getAttribute("user"));
            String sql = "SELECT ID FROM users WHERE username = ?";
            Integer userPermission = jdbcTemplate.queryForObject(sql, new Object[]{session.getAttribute("user")}, Integer.class);
            model.addAttribute("can_post", userPermission);
            return "index";
        }
        return "login";
    }

    // zalogowanie sie i wejscie na strone glowna
    @RequestMapping(value = "/login", method = RequestMethod.POST)
    public String login(final HttpServletRequest request,
                       @ModelAttribute("users") Users user,
                       ModelMap model) {
        if (request.getMethod().equals("POST")) {
            String sql = "SELECT COUNT(*) FROM users WHERE username = ? AND password = ?";
            int count = jdbcTemplate.queryForObject(sql, new Object[]{user.getUsername(), user.getPassword()}, Integer.class);

            if (count > 0) {
                HttpSession session = request.getSession();
                session.setAttribute("user", user.getUsername());
                return "redirect:/";
            } else {
                model.addAttribute("error", "Zły login lub hasło");
                return "login";
            }
        }
        return "login";
    }

    // wylogowanie sie
    @RequestMapping("/logout")
    public String logout(final HttpServletRequest request)
    {
        if (request.getSession(false) != null)
            request.getSession(false).invalidate();
        return "redirect:/";
    }

    // wstawianie posta
    @RequestMapping(value = "/post", method = RequestMethod.POST)
    public String post(HttpServletRequest request, @ModelAttribute("posts") Posts post, @RequestParam("files") List<MultipartFile> files, ModelMap model) {
        if (request.getMethod().equals("POST")) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Timestamp timestamp = Timestamp.from(now);

            String sql = "SELECT MAX(ID) FROM posts";
            Long lastPostId = jdbcTemplate.queryForObject(sql, Long.class);
            if (lastPostId == null) lastPostId = 1L;
            else lastPostId += 1;

            List<String> fileUrls = new ArrayList<>();
            String thumbnailUrl = null;
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    try {
                        String fileName = StringUtils.cleanPath(file.getOriginalFilename());
                        String fileKey = "uploads/" + lastPostId + "/" + fileName;

                        InputStream inputStream = file.getInputStream();
                        ObjectMetadata metadata = new ObjectMetadata();
                        metadata.setContentLength(file.getSize());
                        metadata.setContentType(file.getContentType());

                        amazonS3.putObject(bucketName, fileKey, inputStream, metadata);
                        amazonS3.setObjectAcl(bucketName, fileKey, CannedAccessControlList.Private);

                        String fileUrl = amazonS3.getUrl(bucketName, fileKey).toString();
                        fileUrls.add(fileUrl);

                        if (thumbnailUrl == null) {
                            if (fileName.toLowerCase().endsWith(".pdf")) {
                                thumbnailUrl = createPdfThumbnail(fileKey);
                            } else {
                                thumbnailUrl = fileUrl;
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        model.addAttribute("error", "Nie udało się zapisać pliku " + file.getOriginalFilename());
                        return "redirect:/";
                    }
                }
            }

            String filesCSV = String.join(",", fileUrls);

            sql = "INSERT INTO posts (username, title, description, show_desc, gallery_link, files_path, thumbnail_url, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            int inserted = jdbcTemplate.update(sql, request.getSession().getAttribute("user"), post.getTitle(), post.getDescription(), post.getShow_desc(), post.getGallery_link(), filesCSV, thumbnailUrl, timestamp);
            if (inserted == 0) {
                model.addAttribute("error", "Nie udało się dodać posta.");
            }
        }
        return "redirect:/";
    }

    @RequestMapping(value="/comment", method = RequestMethod.POST)
    public String comment(final HttpServletRequest request,
                          @RequestParam("comment_content") String commentContent,
                          @RequestParam("post_id") int postId,
                          ModelMap model)
    {
        if (request.getMethod().equals("POST")) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Timestamp timestamp = Timestamp.from(now);
            String sql = "INSERT INTO comments (post_id, comment_content, user, created_at) VALUES (?, ?, ?, ?)";
            int inserted = jdbcTemplate.update(sql, postId, commentContent, request.getSession().getAttribute("user"), timestamp);
            if (inserted == 0)
            {
                model.addAttribute("error", "Nie udało się dodać komentarza.");
            }
        }
        return "redirect:/";
    }

    @RequestMapping(value="/comments/{postId}", method = RequestMethod.GET)
    @ResponseBody
    public List<Map> getComments(@PathVariable("postId") int postId) {
        String sql = "SELECT user, comment_content, created_at FROM comments WHERE post_id = ? ORDER BY comment_id DESC";
        return (List<Map>) jdbcTemplate.queryForList(sql, postId);
    }

    @RequestMapping(value="/files/{postId}", method = RequestMethod.GET)
    @ResponseBody
    public List<String> getFilePaths(@PathVariable("postId") int postId) {
        String sql = "SELECT files_path FROM posts WHERE ID = ?";
        String filesCSV = jdbcTemplate.queryForObject(sql, new Object[]{postId}, String.class);

        List<String> filePaths = new ArrayList<>();
        if (filesCSV != null && !filesCSV.isEmpty()) {
            filePaths = Arrays.asList(filesCSV.split(","));
        }

        return filePaths;
    }

    @GetMapping("/download/{postId}/{fileName:.+}")
    public ResponseEntity<Object> downloadFile(@PathVariable("postId") Long postId, @PathVariable("fileName") String fileName) {
        String fileKey = "uploads/" + postId + "/" + fileName;

        try {
            if (!amazonS3.doesObjectExist(bucketName, fileKey)) {
                return ResponseEntity.notFound().build();
            }

            S3Object s3Object = amazonS3.getObject(new GetObjectRequest(bucketName, fileKey));
            InputStream inputStream = s3Object.getObjectContent();

            HttpHeaders headers = new HttpHeaders();

            if (fileName.toLowerCase().endsWith(".pdf")) {
                List<BufferedImage> images = convertPdfToImages(inputStream);

                if (images.isEmpty()) {
                    return ResponseEntity.notFound().build();
                }

                BufferedImage combinedImage = combineImages(images);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(combinedImage, "jpg", baos);
                byte[] imageBytes = baos.toByteArray();
                headers.setContentType(MediaType.IMAGE_JPEG);
                headers.setContentDispositionFormData("attachment", fileName.replace(".pdf", ".jpg"));
                Resource resource = new ByteArrayResource(imageBytes);
                return ResponseEntity.ok()
                        .headers(headers)
                        .body(resource);
            } else {
                headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
                headers.setContentDispositionFormData("attachment", fileName);

                Resource resource = new InputStreamResource(inputStream);

                return ResponseEntity.ok()
                        .headers(headers)
                        .body(resource);
            }

        } catch (AmazonS3Exception e) {
            e.printStackTrace();
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }


    private List<BufferedImage> convertPdfToImages(InputStream inputStream) throws IOException {
        PDDocument document = PDDocument.load(inputStream);
        PDFRenderer renderer = new PDFRenderer(document);

        List<BufferedImage> images = new ArrayList<>();
        for (int page = 0; page < document.getNumberOfPages(); ++page) {
            BufferedImage image = renderer.renderImageWithDPI(page, 300);
            images.add(image);
        }

        document.close();
        return images;
    }

    private BufferedImage combineImages(List<BufferedImage> images) {
        int totalHeight = images.stream().mapToInt(BufferedImage::getHeight).sum();
        int maxWidth = images.stream().mapToInt(BufferedImage::getWidth).max().orElse(0);
        BufferedImage combinedImage = new BufferedImage(maxWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = combinedImage.createGraphics();
        int currentY = 0;
        for (BufferedImage image : images) {
            g2d.drawImage(image, 0, currentY, null);
            currentY += image.getHeight();
        }
        g2d.dispose();
        return combinedImage;
    }
    private String createPdfThumbnail(String fileKey) {
        try {
            S3Object s3Object = amazonS3.getObject(new GetObjectRequest(bucketName, fileKey));
            InputStream inputStream = s3Object.getObjectContent();

            PDDocument document = PDDocument.load(inputStream);
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, 300);
            document.close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            byte[] imageBytes = baos.toByteArray();

            String thumbnailKey = fileKey.replace(".pdf", "_thumbnail.jpg");
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(imageBytes.length);
            metadata.setContentType("image/jpeg");

            amazonS3.putObject(bucketName, thumbnailKey, new ByteArrayInputStream(imageBytes), metadata);
            amazonS3.setObjectAcl(bucketName, thumbnailKey, CannedAccessControlList.Private);

            return amazonS3.getUrl(bucketName, thumbnailKey).toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}